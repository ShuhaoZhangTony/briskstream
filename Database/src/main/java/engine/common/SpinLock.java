package engine.common;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A spinlock based on CAS
 */
public class SpinLock {

    public int count = 0;
    private AtomicReference<Thread> owner = new AtomicReference<>();

    public void Lock() {
        Thread thread = Thread.currentThread();
        if (!Test_Ownership(thread))
            while (!owner.compareAndSet(null, thread)) {
                if (thread.isInterrupted()) break;//to exit program.
            }
//       if(enable_profile)
        count++;
    }

    public void Unlock() {
        Thread thread = Thread.currentThread();
        owner.compareAndSet(thread, null);
    }


    public boolean Try_Lock() {

        Thread thread = Thread.currentThread();
        return owner.compareAndSet(null, thread);
    }

    public boolean Test_Ownership(Thread thread) {
        return owner.compareAndSet(thread, thread);
    }


}
