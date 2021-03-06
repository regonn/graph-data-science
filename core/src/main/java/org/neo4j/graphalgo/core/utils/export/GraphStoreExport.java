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
package org.neo4j.graphalgo.core.utils.export;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.neo4j.batchinsert.internal.TransactionLogsInitializer;
import org.neo4j.common.Validator;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.graphalgo.compat.SettingsProxy;
import org.neo4j.graphalgo.core.loading.GraphStore;
import org.neo4j.internal.batchimport.AdditionalInitialIds;
import org.neo4j.internal.batchimport.BatchImporterFactory;
import org.neo4j.internal.batchimport.Configuration;
import org.neo4j.internal.batchimport.ImportLogic;
import org.neo4j.internal.batchimport.input.Collector;
import org.neo4j.internal.batchimport.input.Input;
import org.neo4j.internal.batchimport.staging.ExecutionMonitors;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.Neo4jLayout;
import org.neo4j.kernel.impl.store.format.RecordFormatSelector;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.logging.internal.NullLogService;
import org.neo4j.logging.internal.StoreLogService;

import java.io.File;
import java.io.IOException;

import static org.neo4j.io.ByteUnit.mebiBytes;
import static org.neo4j.kernel.impl.scheduler.JobSchedulerFactory.createScheduler;

public class GraphStoreExport {

    private final GraphStore graph;

    private final File neo4jHome;

    private final GraphStoreExportConfig config;

    public GraphStoreExport(GraphStore graphStore, File neo4jHome, GraphStoreExportConfig config) {
        this.graph = graphStore;
        this.neo4jHome = neo4jHome;
        this.config = config;
    }

    public void run() {
        run(false);
    }

    /**
     * Runs with default configuration geared towards
     * unit/integration test environments, for example,
     * lower default buffer sizes.
     */
    @TestOnly
    public void runFromTests() {
        run(true);
    }

    private void run(boolean defaultSettingsSuitableForTests) {
        DIRECTORY_IS_WRITABLE.validate(neo4jHome);
        var databaseConfig = Config.defaults(GraphDatabaseSettings.neo4j_home, neo4jHome.toPath());
        var databaseLayout = Neo4jLayout.of(databaseConfig).databaseLayout(config.dbName());
        var importConfig = getImportConfig(defaultSettingsSuitableForTests);

        var lifeSupport = new LifeSupport();

        try (FileSystemAbstraction fs = new DefaultFileSystemAbstraction()) {
            var logService = config.enableDebugLog()
                ? lifeSupport.add(StoreLogService.withInternalLog(databaseConfig.get(SettingsProxy.storeInternalLogPath()).toFile()).build(fs))
                : NullLogService.getInstance();
            var jobScheduler = lifeSupport.add(createScheduler());

            lifeSupport.start();

            Input input = new GraphStoreInput(graph, config.batchSize());

            var importer = BatchImporterFactory.withHighestPriority().instantiate(
                databaseLayout,
                fs,
                null, // no external page cache
                importConfig,
                logService,
                ExecutionMonitors.invisible(),
                AdditionalInitialIds.EMPTY,
                databaseConfig,
                RecordFormatSelector.selectForConfig(databaseConfig, logService.getInternalLogProvider()),
                ImportLogic.NO_MONITOR,
                jobScheduler,
                Collector.EMPTY,
                TransactionLogsInitializer.INSTANCE
            );
            importer.doImport(input);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            lifeSupport.shutdown();
        }
    }

    @NotNull
    private Configuration getImportConfig(boolean defaultSettingsSuitableForTests) {
        return new Configuration() {
            @Override
            public int maxNumberOfProcessors() {
                return config.writeConcurrency();
            }

            @Override
            public long pageCacheMemory() {
                return defaultSettingsSuitableForTests ? mebiBytes(8) : Configuration.super.pageCacheMemory();
            }

            @Override
            public boolean highIO() {
                return false;
            }
        };
    }

    private static final Validator<File> DIRECTORY_IS_WRITABLE = value -> {
        if (value.mkdirs()) {   // It's OK, we created the directory right now, which means we have write access to it
            return;
        }

        var test = new File(value, "_______test___");
        try {
            test.createNewFile();
        } catch (IOException e) {
            throw new IllegalArgumentException("Directory '" + value + "' not writable: " + e.getMessage());
        } finally {
            test.delete();
        }
    };
}
