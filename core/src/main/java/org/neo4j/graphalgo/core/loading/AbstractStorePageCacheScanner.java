/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.core.loading;

import org.neo4j.graphalgo.compat.GraphDatabaseApiProxy;
import org.neo4j.graphalgo.core.utils.BitUtil;
import org.neo4j.graphalgo.core.utils.paged.PaddedAtomicLong;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.format.RecordFormat;
import org.neo4j.kernel.impl.store.format.RecordFormats;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;
import org.neo4j.kernel.impl.store.record.RecordLoad;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.neo4j.kernel.impl.store.RecordPageLocationCalculator.offsetForId;


public class AbstractStorePageCacheScanner<Record extends AbstractBaseRecord> {

    public static final int DEFAULT_PREFETCH_SIZE = 100;

    public interface Access<Record extends AbstractBaseRecord> {
        /**
         * Return the store to use.
         */
        RecordStore<Record> store(NeoStores neoStores);

        /**
         * Return the record format to use.
         */
        RecordFormat<Record> recordFormat(RecordFormats formats);

        /**
         * Return the filename of the store file that the page cache maps
         */
        String storeFileName();

        /**
         * Create a new scanner for the selected access type
         */
        AbstractStorePageCacheScanner<Record> newScanner(GraphDatabaseService api, int prefetchSize);
    }

    public interface RecordConsumer<Record extends AbstractBaseRecord> {
        /**
         * Imports the record at a given position and return the new position.
         * Can also ignore the record if it is not of interest.
         */
        void offer(Record record);
    }

    public final class Cursor implements AutoCloseable {

        // last page to contain a value of interest, inclusive, but we mostly
        // treat is as exclusive since we want to special-case the last page
        private final long lastPage;
        // end offset of the last page, exclusive (first offset to be out-of-range)
        private final int lastOffset;

        // thread-local cursor instance
        private PageCursor pageCursor;
        // thread-local record instance
        private Record record;

        // the current record id -
        private long recordId;
        // the current page
        private long currentPage;
        // the last page that has already been fetched - exclusive
        private long fetchedUntilPage;
        // the current offset into the page
        private int offset;

        Cursor(PageCursor pageCursor, Record record) {
            this.lastOffset = offsetForId(maxId, pageSize, recordSize);
            this.lastPage = calculateLastPageId(maxId, recordsPerPage, lastOffset);
            this.pageCursor = pageCursor;
            this.record = record;
            this.offset = pageSize; // trigger page load as first action
            this.currentPage = -1;
        }

        int bulkSize() {
            return prefetchSize * recordsPerPage;
        }

        private long calculateLastPageId(long maxId, long recordsPerPage, int lastPageOffset) {
            long lastPageId = BitUtil.ceilDiv(maxId, recordsPerPage) - 1L;
            lastPageId = Math.max(lastPageId, 0L);
            if (lastPageOffset == 0) {
                lastPageId += 1;
            }
            return lastPageId;
        }

        boolean bulkNext(RecordConsumer<Record> consumer) {
            try {
                return bulkNext0(consumer);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private boolean bulkNext0(RecordConsumer<Record> consumer) throws IOException {
            if (recordId == -1L) {
                return false;
            }

            int endOffset;
            long page;
            long endPage;
            if (currentPage < lastPage) {
                preFetchPages();
            }
            if (currentPage == lastPage) {
                page = lastPage;
                endOffset = lastOffset;
                endPage = 1L + page;
            } else if (currentPage > lastPage) {
                this.recordId = -1L;
                return false;
            } else {
                page = currentPage;
                endPage = fetchedUntilPage;
                endOffset = AbstractStorePageCacheScanner.this.pageSize;
            }

            int offset = this.offset;
            long recordId = page * (long) recordsPerPage;
            int recordSize = AbstractStorePageCacheScanner.this.recordSize;
            PageCursor pageCursor = this.pageCursor;
            Record record = this.record;

            while (page < endPage) {
                if (!pageCursor.next(page++)) {
                    break;
                }
                offset = 0;

                while (offset < endOffset) {
                    record.setId(recordId++); // do we need this setId command here?
                    loadAtOffset(offset);
                    offset += recordSize;
                    if (record.inUse()) {
                        consumer.offer(record);
                    }
                }
            }

            currentPage = page;
            this.offset = offset;
            this.recordId = recordId;

            return true;
        }

        private void preFetchPages() throws IOException {
            PageCursor pageCursor = this.pageCursor;
            long prefetchSize = AbstractStorePageCacheScanner.this.prefetchSize;
            long startPage = nextPageId.getAndAdd(prefetchSize);
            long endPage = Math.min(lastPage, startPage + prefetchSize);
            long preFetchedPage = startPage;
            while (preFetchedPage < endPage) {
                if (!pageCursor.next(preFetchedPage)) {
                    break;
                }
                ++preFetchedPage;
            }
            this.currentPage = startPage;
            this.fetchedUntilPage = preFetchedPage;
        }

        private void loadAtOffset(int offset) throws IOException {
            do {
                record.setInUse(false);
                pageCursor.setOffset(offset);
                recordFormat.read(record, pageCursor, RecordLoad.CHECK, recordSize);
            } while (pageCursor.shouldRetry());
            verifyLoad();
        }

        private void verifyLoad() {
            pageCursor.checkAndClearBoundsFlag();
        }

        @Override
        public void close() {
            if (pageCursor != null) {
                pageCursor.close();
                pageCursor = null;
                record = null;

                final Cursor localCursor = cursors.get();
                // sanity check, should always be called from the same thread
                if (localCursor == this) {
                    cursors.remove();
                }
            }
        }
    }

    // fetch this many pages at once
    private final int prefetchSize;
    // global pointer which block of pages need to be fetched next
    private final AtomicLong nextPageId;
    // global cursor pool to return this one to
    private final ThreadLocal<Cursor> cursors;

    // size in bytes of a single record - advance the offset by this much
    private final int recordSize;
    // how many records are there in a single page
    private final int recordsPerPage;

    private final long maxId;
    private final int pageSize;
    // how to read the record
    private final RecordFormat<Record> recordFormat;
    private final RecordStore<Record> store;
    private final PagedFile pagedFile;

    AbstractStorePageCacheScanner(
        int prefetchSize,
        GraphDatabaseService api,
        Access<Record> access) {

        NeoStores neoStores = GraphDatabaseApiProxy.neoStores(api);

        RecordStore<Record> store = access.store(neoStores);
        int recordSize = store.getRecordSize();
        int recordsPerPage = store.getRecordsPerPage();
        int pageSize = recordsPerPage * recordSize;

        PagedFile pagedFile = null;
        PageCache pageCache = GraphDatabaseApiProxy.resolveDependency(api, PageCache.class);
        String storeFileName = access.storeFileName();
        try {
            for (PagedFile pf : pageCache.listExistingMappings()) {
                if (pf.file().getName().equals(storeFileName)) {
                    pageSize = pf.pageSize();
                    recordsPerPage = pageSize / recordSize;
                    pagedFile = pf;
                    break;
                }
            }
        } catch (IOException ignored) {
        }

        this.prefetchSize = prefetchSize;
        this.nextPageId = new PaddedAtomicLong();
        this.cursors = new ThreadLocal<>();
        this.recordSize = recordSize;
        this.recordsPerPage = recordsPerPage;
        this.maxId = 1L + store.getHighestPossibleIdInUse();
        this.pageSize = pageSize;
        this.recordFormat = access.recordFormat(neoStores.getRecordFormats());
        this.store = store;
        this.pagedFile = pagedFile;
    }

    public final Cursor getCursor() {
        Cursor cursor = cursors.get();
        if (cursor == null) {
            // Don't add as we want to always call next as the first cursor action,
            // which actually does the advance and returns the correct cursor.
            // This is just to position the page cursor somewhere in the vicinity
            // of its actual next page.
            long next = nextPageId.get();

            PageCursor pageCursor;
            try {
                if (pagedFile != null) {
                    pageCursor = pagedFile.io(next, PagedFile.PF_READ_AHEAD | PagedFile.PF_SHARED_READ_LOCK);
                } else {
                    long recordId = next * (long) recordSize;
                    pageCursor = store.openPageCursorForReading(recordId);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Record record = store.newRecord();
            cursor = new Cursor(pageCursor, record);
            cursors.set(cursor);
        }
        return cursor;
    }

    final long storeSize() {
        if (pagedFile != null) {
            return pagedFile.file().length();
        }
        long recordsInUse = 1L + store.getHighestPossibleIdInUse();
        long idsInPages = ((recordsInUse + (recordsPerPage - 1L)) / recordsPerPage) * recordsPerPage;
        return idsInPages * (long) recordSize;
    }

    RecordStore<Record> store() {
        return store;
    }
}
