package applications.param.lr;

import applications.datatype.PositionReport;
import engine.storage.SchemaRecordRef;

/**
 * Currently only consider position events.
 */
public class LREvent {

    private final int tthread;
    private final long bid;

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getTimestamp() {
        return timestamp;
    }

    private long timestamp;
    public int count;
    public double lav;
    private PositionReport posreport;//input_event associated meta data.
//    private final AvgVehicleSpeedTuple vsreport;//intermediate input.


    public SchemaRecordRef speed_value;
    public SchemaRecordRef count_value;

    /**
     * creating a new LREvent.
     *
     * @param posreport
     * @param tthread
     * @param bid
     */
    public LREvent(PositionReport posreport, int tthread, long bid) {
        this.posreport = posreport;
        this.tthread = tthread;
//        vsreport = vehicleSpeedTuple;
        this.bid = bid;
        speed_value = new SchemaRecordRef();
        count_value = new SchemaRecordRef();
    }


    public PositionReport getPOSReport() {
        return posreport;
    }


    public int getPid() {
        return posreport.getSegment() % tthread;//which partition does this input_event belongs to.
    }

    public long getBid() {
        return bid;
    }


}