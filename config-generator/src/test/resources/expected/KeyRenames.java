/*
 * Copyright (c) 2017-2019 "Neo4j,"
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
package positive;

import org.jetbrains.annotations.NotNull;
import org.neo4j.graphalgo.core.CypherMapWrapper;

import javax.annotation.Generated;

@Generated("org.neo4j.graphalgo.proc.ConfigurationProcessor")
public final class KeyRenamesConfig implements KeyRenames {

    private final int lookupUnderAnotherKey;

    private final int whitespaceWillBeTrimmed;

    public KeyRenamesConfig(@NotNull CypherMapWrapper config) {
        this.lookupUnderAnotherKey = config.requireInt("key could also be an invalid identifier");
        this.whitespaceWillBeTrimmed = config.requireInt("whitespace will be trimmed");
    }

    public KeyRenamesConfig(int lookupUnderAnotherKey, int whitespaceWillBeTrimmed) {
        this.lookupUnderAnotherKey = lookupUnderAnotherKey;
        this.whitespaceWillBeTrimmed = whitespaceWillBeTrimmed;
    }

    @Override
    public int lookupUnderAnotherKey() {
        return this.lookupUnderAnotherKey;
    }

    @Override
    public int whitespaceWillBeTrimmed() {
        return this.whitespaceWillBeTrimmed;
    }
}