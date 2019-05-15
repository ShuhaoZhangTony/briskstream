package applications;

public interface CONTROL {

    //global settings.
    int kMaxThreadNum = 40;

    int MeasureStart = 50_000;//server needs at least 10,000 to compile, so skip them.

    int MeasureBound = 100_000;

    //application related.
    int NUM_EVENTS = 100_000; //different input events..

    int TEST_NUM_EVENST = 100_000;//total runs per thread.

    //combo optimization
    boolean enable_app_combo = true;//compose all operators into one.

    int combo_bid_size = 1;//reduce conflict. NOT applicable to LAL, LWM and PAT (must set to one).

    int sink_combo_bid_size = 1000;//reduce conflict. NOT applicable to LAL, LWM and PAT (must set to one).

    int MIN_EVENTS_PER_THREAD = NUM_EVENTS / combo_bid_size / kMaxThreadNum;


    //order related.

    boolean enable_force_ordering = true;

    //db related.
    boolean enable_shared_state = true;//this is for transactional state mgmt.

    boolean enable_states_partition = true;//must be enabled for PAT/SSTORE.

    boolean enable_TSTREAM = false;


    //pre- and post -compute

    boolean enable_pre_compute = false;//not in use.
    boolean enable_post_compute = true;

    //latency related.

    boolean enable_latency_measurement = false;//
//    boolean enable_admission_control = enable_latency_measurement;//only enable for TStream

    //profile related.
    boolean enable_profile = true;//enable this only when we want to test for breakdown.

    boolean enable_debug = false;//some critical debug section.

    //engine related.
    boolean enable_engine = true;//1. enable TP_engine. Always enabled. There's no meaning if we disable engine for T-Stream.
    boolean enable_numa_placement = true;//thread placement. always on.

    //used for NUMA-aware partition engine
    boolean enable_work_partition = false; // 2. this is a sub-option, only useful when engine is enabled.
    int island = -1;//-1 stands for one engine per core; -2 stands for one engine per socket.
    int CORE_PER_SOCKET = 10;//configure this for NUMA placement please.
    int NUM_OF_SOCKETS = 4;//configure this for NUMA placement please.

    //single engine with work-stealing.
    boolean enable_work_stealing = true; // 3. this is a sub-option, only useful when engine is enabled. enable by default -- should be enabled for island=-1.
    boolean enable_mvcc = (!enable_work_partition || island != -1) && enable_work_stealing;//true only when partition is false and island is not -1

    boolean enable_speculative = false;//work in future!


//    boolean enable_pushdown = false;//enabled by default.

}