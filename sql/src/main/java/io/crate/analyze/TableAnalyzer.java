/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.analyze;

import io.crate.exceptions.PartitionUnknownException;
import io.crate.metadata.PartitionName;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.sql.tree.Assignment;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

final class TableAnalyzer {

    static Collection<String> filteredIndices(ParameterContext parameterContext,
                                              List<Assignment> partitionProperties,
                                              DocTableInfo tableInfo) {
        if (partitionProperties.isEmpty()) {
            return Arrays.asList(tableInfo.concreteIndices());
        } else {
            DocTableInfo docTableInfo = tableInfo;
            PartitionName partitionName = PartitionPropertiesAnalyzer.toPartitionName(
                docTableInfo,
                partitionProperties,
                parameterContext.parameters()
            );
            if (!docTableInfo.partitions().contains(partitionName)) {
                throw new PartitionUnknownException(tableInfo.ident().fqn(), partitionName.ident());
            }
            return Collections.singletonList(partitionName.asIndexName());
        }
    }
}