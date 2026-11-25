package app.organicmaps.transit;

import android.content.Context;
import app.organicmaps.sdk.util.concurrency.UiThread;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One install shared by routing and settings; route cancellation never discards downloaded data. */
final class RigaTimetableInstaller
{
  interface Task { void run() throws Exception; }
  private static RigaTimetableInstaller sInstance;
  static synchronized RigaTimetableInstaller get(Context context)
  {
    if (sInstance == null)
    {
      Context app = context.getApplicationContext();
      sInstance = new RigaTimetableInstaller(Executors.newSingleThreadExecutor(), UiThread::run,
                                             () -> RigaTransitFeeds.update(app));
    }
    return sInstance;
  }
  private final Executor mWorker, mUi;
  private final Task mTask;
  private final List<Consumer<Exception>> mListeners = new ArrayList<>();
  private boolean mRunning;
  RigaTimetableInstaller(Executor worker, Executor ui, Task task) { mWorker=worker; mUi=ui; mTask=task; }
  synchronized boolean isRunning() { return mRunning; }
  synchronized void request(Consumer<Exception> listener)
  {
    mListeners.add(listener);
    if (mRunning) return;
    mRunning=true;
    mWorker.execute(() -> {
      Exception error=null;
      try { mTask.run(); } catch (Exception e) { error=e; }
      List<Consumer<Exception>> callbacks;
      synchronized (this) { callbacks=new ArrayList<>(mListeners);mListeners.clear();mRunning=false; }
      Exception result=error;
      mUi.execute(() -> { for (Consumer<Exception> callback:callbacks) callback.accept(result); });
    });
  }
}
