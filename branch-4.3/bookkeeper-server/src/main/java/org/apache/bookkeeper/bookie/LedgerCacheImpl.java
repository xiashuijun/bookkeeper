/*
 *
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
 *
 */

package org.apache.bookkeeper.bookie;

import java.io.IOException;

import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.util.SnapshotMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of LedgerCache interface.
 * This class serves two purposes.
 */
public class LedgerCacheImpl implements LedgerCache {
    private final static Logger LOG = LoggerFactory.getLogger(LedgerCacheImpl.class);

    private final IndexInMemPageMgr indexPageManager;
    private final IndexPersistenceMgr indexPersistenceManager;
    private final int pageSize;
    private final int entriesPerPage;

    public LedgerCacheImpl(ServerConfiguration conf, SnapshotMap<Long, Boolean> activeLedgers,
                           LedgerDirsManager ledgerDirsManager) throws IOException {
        this(conf, activeLedgers, ledgerDirsManager, NullStatsLogger.INSTANCE);
    }

    public LedgerCacheImpl(ServerConfiguration conf, SnapshotMap<Long, Boolean> activeLedgers,
                           LedgerDirsManager ledgerDirsManager, StatsLogger statsLogger) throws IOException {
        this.pageSize = conf.getPageSize();
        this.entriesPerPage = pageSize / 8;
        this.indexPersistenceManager = new IndexPersistenceMgr(pageSize, entriesPerPage, conf, activeLedgers,
                ledgerDirsManager, statsLogger);
        this.indexPageManager = new IndexInMemPageMgr(pageSize, entriesPerPage, conf,
                indexPersistenceManager, statsLogger);
    }

    IndexPersistenceMgr getIndexPersistenceManager() {
        return indexPersistenceManager;
    }

    IndexInMemPageMgr getIndexPageManager() {
        return indexPageManager;
    }

    /**
     * @return page size used in ledger cache
     */
    public int getPageSize() {
        return pageSize;
    }

    @Override
    public void putEntryOffset(long ledger, long entry, long offset) throws IOException {
        indexPageManager.putEntryOffset(ledger, entry, offset);
    }

    @Override
    public long getEntryOffset(long ledger, long entry) throws IOException {
        return indexPageManager.getEntryOffset(ledger, entry);
    }

    @Override
    public void flushLedger(boolean doAll) throws IOException {
        indexPageManager.flushOneOrMoreLedgers(doAll);
    }

    @Override
    public long getLastEntry(long ledgerId) throws IOException {
        // Get the highest entry from the pages that are in memory
        long lastEntryInMem = indexPageManager.getLastEntryInMem(ledgerId);
        // Some index pages may have been evicted from memory, retrieve the last entry
        // from the persistent store. We will check if there could be an entry beyond the
        // last in mem entry and only then attempt to get the last persisted entry from the file
        // The latter is just an optimization
        long lastEntry = indexPersistenceManager.getPersistEntryBeyondInMem(ledgerId, lastEntryInMem);
        return lastEntry;
    }

    /**
     * This method is called whenever a ledger is deleted by the BookKeeper Client
     * and we want to remove all relevant data for it stored in the LedgerCache.
     */
    @Override
    public void deleteLedger(long ledgerId) throws IOException {
        LOG.debug("Deleting ledgerId: {}", ledgerId);

        indexPageManager.removePagesForLedger(ledgerId);
        indexPersistenceManager.removeLedger(ledgerId);
    }

    @Override
    public byte[] readMasterKey(long ledgerId) throws IOException, BookieException {
        return indexPersistenceManager.readMasterKey(ledgerId);
    }

    @Override
    public boolean setFenced(long ledgerId) throws IOException {
        return indexPersistenceManager.setFenced(ledgerId);
    }

    @Override
    public boolean isFenced(long ledgerId) throws IOException {
        return indexPersistenceManager.isFenced(ledgerId);
    }

    @Override
    public void setMasterKey(long ledgerId, byte[] masterKey) throws IOException {
        indexPersistenceManager.setMasterKey(ledgerId, masterKey);
    }

    @Override
    public boolean ledgerExists(long ledgerId) throws IOException {
        return indexPersistenceManager.ledgerExists(ledgerId);
    }

    @Override
    public LedgerCacheBean getJMXBean() {
        return new LedgerCacheBean() {
            @Override
            public String getName() {
                return "LedgerCache";
            }

            @Override
            public boolean isHidden() {
                return false;
            }

            @Override
            public int getPageCount() {
                return LedgerCacheImpl.this.indexPageManager.getNumUsedPages();
            }

            @Override
            public int getPageSize() {
                return LedgerCacheImpl.this.getPageSize();
            }

            @Override
            public int getOpenFileLimit() {
                return LedgerCacheImpl.this.indexPersistenceManager.getOpenFileLimit();
            }

            @Override
            public int getPageLimit() {
                return LedgerCacheImpl.this.indexPageManager.getPageLimit();
            }

            @Override
            public int getNumOpenLedgers() {
                return LedgerCacheImpl.this.indexPersistenceManager.getNumOpenLedgers();
            }
        };
    }

    @Override
    public void close() throws IOException {
        indexPersistenceManager.close();
    }
}
