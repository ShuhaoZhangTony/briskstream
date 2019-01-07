package engine;

import engine.storage.EventManager;
import engine.storage.StorageManager;
import engine.storage.TableRecord;
import engine.storage.table.RecordSchema;

import java.io.IOException;

public abstract class Database {
    public int numTransactions = 0;//current number of activate transactions
    StorageManager storageManager;
    public EventManager eventManager;

//	public transient TxnParam param;

    /**
     * Close this database.
     */
    public synchronized void close() throws IOException {
        storageManager.close();
    }

    /**
     *
     */
    public void dropAllTables() throws IOException {
        storageManager.dropAllTables();
    }

    /**
     * @param tableSchema
     * @param tableName
     */
    public void createTable(RecordSchema tableSchema, String tableName) {
        try {
            storageManager.createTable(tableSchema, tableName);
        } catch (DatabaseException e) {
            e.printStackTrace();
        }
    }


    public abstract void InsertRecord(String table, TableRecord record) throws DatabaseException;

    public StorageManager getStorageManager() {
        return storageManager;
    }


}
