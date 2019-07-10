package applications.general.bolts.comm;

import applications.parser.StringParser;
import applications.general.spout.helper.parser.Parser;
import applications.util.Configuration;
import brisk.components.operators.base.MapBolt;
import brisk.execution.runtime.tuple.JumboTuple;
import brisk.execution.runtime.tuple.impl.Fields;
import brisk.execution.runtime.tuple.impl.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by tony on 5/5/2017.
 * Use char[] to represent string!
 */
public class StringParserBolt extends MapBolt {
    private static final long serialVersionUID = 7613878877612069900L;
    private static final Logger LOG = LoggerFactory.getLogger(StringParserBolt.class);
    final StringParser parser;
    private final Fields fields;

    public StringParserBolt(Parser parser, Fields fields) {
        super(LOG);
        this.parser = (StringParser) parser;
        this.fields = fields;
    }

    public Integer default_scale(Configuration conf) {

        int numNodes = conf.getInt("num_socket", 1);
        if (numNodes == 8) {
            return 4;
        } else {
            return 1;
        }


    }

    @Override
    public Fields getDefaultFields() {
        return fields;
    }

    @Override
    public void execute(Tuple in) throws InterruptedException {
        char[] string = in.getCharArray(0);
        char[] emit = parser.parse(string);
        collector.force_emit(emit);

    }

    @Override
    public void execute(JumboTuple in) throws InterruptedException {
//		final long bid = in.getBID();
        int bound = in.length;
        for (int i = 0; i < bound; i++) {

			/*
			String string = in.getString(0, i);
			List<StreamValues> emit = parser.parse(string);
			for (StreamValues values : emit) {
				collector.emit(-1L, values.GetAndUpdate(0));
			}
			*/
            char[] string = in.getCharArray(0, i);
            char[] emit = parser.parse(string);
            collector.emit(0, emit);

        }
    }

    @Override
    public void profile_execute(JumboTuple in) throws InterruptedException {
        int bound = in.length;
        for (int i = 0; i < bound; i++) {
            char[] string = in.getCharArray(0, i);
            char[] emit = parser.parse(string);
            collector.emit_nowait(emit);
        }

    }

}
