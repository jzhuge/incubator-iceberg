/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.catalog;

import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.exceptions.AlreadyExistsException;

public class CachingCatalog implements Catalog {

  public static Catalog wrap(Catalog catalog) {
    return new CachingCatalog(catalog);
  }

  private final Cache<TableIdentifier, Table> tableCache = CacheBuilder.newBuilder().softValues().build();
  private final Catalog catalog;

  private CachingCatalog(Catalog catalog) {
    this.catalog = catalog;
  }

  @Override
  public Table loadTable(TableIdentifier ident) {
    try {
      return tableCache.get(ident, () -> catalog.loadTable(ident));
    } catch (UncheckedExecutionException | ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), RuntimeException.class);
      throw new RuntimeException("Failed to load table: " + ident, e.getCause());
    }
  }

  @Override
  public Table createTable(TableIdentifier ident, Schema schema, PartitionSpec spec, String location,
                           Map<String, String> properties) {
    AtomicBoolean created = new AtomicBoolean(false);
    Table table;
    try {
      table = tableCache.get(ident, () -> {
        created.set(true);
        return catalog.createTable(ident, schema, spec, location, properties);
      });
    } catch (UncheckedExecutionException | ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), RuntimeException.class);
      throw new RuntimeException("Failed to load table: " + ident, e.getCause());
    }

    if (!created.get()) {
      throw new AlreadyExistsException("Table already exists: %s", ident);
    }

    return table;
  }

  @Override
  public Transaction newCreateTableTransaction(TableIdentifier ident, Schema schema, PartitionSpec spec,
                                               String location, Map<String, String> properties) {
    // create a new transaction without altering the cache. the table doesn't exist until the transaction is committed.
    // if the table is created before the transaction commits, any cached version is correct and the transaction create
    // will fail. if the transaction commits before another create, then the cache will be empty.
    return catalog.newCreateTableTransaction(ident, schema, spec, location, properties);
  }

  @Override
  public Transaction newReplaceTableTransaction(TableIdentifier ident, Schema schema, PartitionSpec spec,
                                                String location, Map<String, String> properties, boolean orCreate) {
    // create a new transaction without altering the cache. the table doesn't change until the transaction is committed.
    // when the transaction commits, invalidate the table in the cache if it is present.
    return CommitCallbackTransaction.addCallback(
        catalog.newReplaceTableTransaction(ident, schema, spec, location, properties, orCreate),
        () -> tableCache.invalidate(ident));
  }

  @Override
  public boolean dropTable(TableIdentifier ident, boolean purge) {
    boolean dropped = catalog.dropTable(ident, false);
    tableCache.invalidate(ident);
    return dropped;
  }

  @Override
  public void renameTable(TableIdentifier from, TableIdentifier to) {
    catalog.renameTable(from, to);
    tableCache.invalidate(from);
  }

}