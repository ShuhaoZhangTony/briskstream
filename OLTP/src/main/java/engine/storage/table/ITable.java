package engine.storage.table;

import engine.storage.TableRecord;
import engine.storage.TableRecords;

import java.io.Closeable;

public interface ITable extends Iterable<TableRecord>, Closeable {

    /**
     * @param primary_key
     * @return we have to return the d_record here as no pointer passing in Java, contrasting to C/CPP.
     */
    TableRecord SelectKeyRecord(String primary_key);


    void SelectRecords(int idx_id, String secondary_key, TableRecords records);

}
