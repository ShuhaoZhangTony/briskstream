package applications.spout;

import applications.util.Configuration;
import applications.util.OsUtils;
import brisk.components.operators.api.AbstractSpout;
import brisk.execution.ExecutionGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

public class MemFileSpout extends AbstractSpout {
    private static final Logger LOG = LoggerFactory.getLogger(MemFileSpout.class);
    private static final long serialVersionUID = -2394340130331865581L;

    protected int element = 0;

    public MemFileSpout() {
        super(LOG);
        this.scalable = false;
    }

    @Override
    public Integer default_scale(Configuration conf) {

        int numNodes = conf.getInt("num_socket", 1);
        if (numNodes == 8) {
            return 2;
        } else {
            return 1;
        }
    }


    @Override
    public void initialize(int thread_Id, int thisTaskId, ExecutionGraph graph) {
        LOG.info("Spout initialize is being called");
        cnt = 0;
        counter = 0;
        taskId = getContext().getThisTaskIndex();//context.getThisTaskId(); start from 0..
        load_input();

    }

    /**
     * relax_reset source messages.
     */
    @Override
    public void cleanup() {

    }

//	protected void reset_index() {
//		if (timestamp_counter == array.length) {
//			timestamp_counter = 0;
//		}
//	}


//	int control = 1;

//	volatile String emit;


    @Override
    public void nextTuple() throws InterruptedException {
//        String[] value_list = new String[batch];
//        for (int i = 0; i < batch; i++) {
//            value_list[i] = array[timestamp_counter];
//        }
//
//		emit = array[timestamp_counter]+"";
//		if (control > 0) {
        collector.emit(array_array[counter]);//Arrays.copyOf(array_array[timestamp_counter], array_array[timestamp_counter].length) a workaround to ensure char array instead of string is used in transmission.
//		collector.emit_nowait(new StreamValues(array[timestamp_counter]));
        counter++;
        if (counter == array_array.length) {
            counter = 0;
        }
//		reset_index();
//			control--;
    }

    @Override
    public void nextTuple_nonblocking() throws InterruptedException {

//		collector.emit(array[timestamp_counter]);//Arrays.copyOf(array[timestamp_counter], array[timestamp_counter].length) a workaround to ensure char array instead of string is used in transmission.
        collector.emit_nowait(array_array[counter]);
        counter++;
        if (counter == array_array.length) {
            counter = 0;
        }

    }

    public void display() {
        LOG.info("timestamp_counter:" + counter);
    }

}