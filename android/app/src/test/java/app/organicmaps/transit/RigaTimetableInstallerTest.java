package app.organicmaps.transit;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class RigaTimetableInstallerTest
{
  static final class Queue implements Executor
  {
    final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
    public void execute(Runnable task) { tasks.add(task); }
    void finish() { while(!tasks.isEmpty()) tasks.remove().run(); }
  }
  @Test public void routingAndSettingsJoinOneInstallThatCompletesIndependentlyOfRoute()
  {
    Queue work=new Queue(),ui=new Queue();AtomicInteger installs=new AtomicInteger(),callbacks=new AtomicInteger();
    RigaTimetableInstaller installer=new RigaTimetableInstaller(work,ui,installs::incrementAndGet);
    installer.request(error->{assertNull(error);callbacks.incrementAndGet();});
    installer.request(error->{assertNull(error);callbacks.incrementAndGet();});
    assertTrue(installer.isRunning());assertEquals(1,work.tasks.size());assertEquals(0,callbacks.get());
    // A pending dependency has not answered; callers join it, rather than starting another download.
    installer.request(error->callbacks.incrementAndGet());assertEquals(1,work.tasks.size());
    work.finish();assertFalse(installer.isRunning());assertEquals(1,installs.get());
    ui.finish();assertEquals(3,callbacks.get());
  }
  @Test public void timeoutOrErrorNotifiesEveryListenerAndAllowsRetry()
  {
    for(boolean timeout:new boolean[]{false,true})
    {
      Queue work=new Queue(),ui=new Queue();AtomicInteger attempts=new AtomicInteger(),errors=new AtomicInteger();
      RigaTimetableInstaller installer=new RigaTimetableInstaller(work,ui,()->{
        if(attempts.getAndIncrement()==0)
        {
          if(timeout) throw new java.net.SocketTimeoutException("dependency never answers");
          throw new java.io.IOException("dependency error");
        }
      });
      installer.request(error->{assertNotNull(error);errors.incrementAndGet();});
      installer.request(error->{assertNotNull(error);errors.incrementAndGet();});
      work.finish();ui.finish();assertEquals(2,errors.get());assertFalse(installer.isRunning());
      installer.request(error->assertNull(error));work.finish();ui.finish();assertEquals(2,attempts.get());
    }
  }
}
