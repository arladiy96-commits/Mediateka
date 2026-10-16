package com.serialtransfer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Viewer-only remote control and microSD download worker. No HTTP server. */
public final class TransferService extends Service {
    static final String START_RECEIVER = "start_receiver";
    static final String STOP_RECEIVER = "stop_receiver";
    static final String DOWNLOAD = "download";
    static final String PAUSE = "pause";
    private static final String CHANNEL = "mediateka_viewer_transfer";
    private static final int NOTICE = 87;
    static volatile boolean downloading;
    static volatile int queueRevision;
    static volatile String progressText = "Загрузки ожидают";
    private volatile boolean receiverRunning;
    private Thread receiverThread;
    private volatile DownloadEngine engine;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private PowerManager.WakeLock wake;
    private long lastDiscovery;

    static void send(Context c, String command) {
        Intent in = new Intent(c, TransferService.class).putExtra("command", command);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(in); else c.startService(in);
    }
    static void download(Context c, String host, String code, String folder, JSONArray items) {
        Intent in = new Intent(c, TransferService.class).putExtra("command", DOWNLOAD)
                .putExtra("host", host).putExtra("code", code).putExtra("folder", folder)
                .putExtra("items", items.toString());
        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(in);else c.startService(in);
    }
    @Override public void onCreate(){
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel(CHANNEL,"Медиатека · просмотр",NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        startForeground(NOTICE,notification("Готов к просмотру"));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?START_RECEIVER:intent.getStringExtra("command");
        if(START_RECEIVER.equals(action)) startReceiver();
        else if(STOP_RECEIVER.equals(action)) stopReceiver();
        else if(DOWNLOAD.equals(action)) startDownload(intent);
        else if(PAUSE.equals(action)) pause();
        return START_STICKY;
    }
    private synchronized void startReceiver(){
        if(receiverRunning)return;
        if(!MediaLibrary.prefs(this).getString("remote_code","").matches("[0-9]{8}")){stopIfIdle();return;}
        receiverRunning=true;
        receiverThread=new Thread(()->{
            while(receiverRunning){
                boolean reachable = false;
                try {
                    String ip=MediaLibrary.prefs(this).getString("remote_host","");
                    String code=MediaLibrary.prefs(this).getString("remote_code","");
                    if(!ip.isEmpty()) {
                        JSONObject command=RemoteControlClient.poll(this,ip,code);
                        reachable = true;
                        if(!"none".equals(command.optString("action","none"))){
                            if("play".equals(command.optString("action"))){
                                try {
                                    JSONObject list=new JSONObject(DownloadEngine.list(ip,code));
                                    JSONArray files=list.optJSONArray("files");
                                    if(files!=null)MediaLibrary.prefs(this).edit()
                                            .putString("remote_cache",files.toString()).apply();
                                } catch(Exception ignored) { }
                            }
                            act(command);
                        }
                        if(!downloading && !DownloadQueue.paused(this) && DownloadQueue.waiting(this)>0){
                            try {
                                JSONObject list=new JSONObject(DownloadEngine.list(ip,code));
                                JSONArray all=list.optJSONArray("files");
                                if(all!=null) {
                                    JSONArray jobs=DownloadQueue.availablePending(this,all);
                                    String folder=MediaLibrary.prefs(this).getString(MediaLibrary.DEST_TREE,"");
                                    if(!folder.isEmpty()&&jobs.length()>0) {
                                        startDownload(new Intent(this,TransferService.class)
                                                .putExtra("host",ip).putExtra("code",code)
                                                .putExtra("folder",folder).putExtra("items",jobs.toString()));
                                    }
                                }
                            } catch(Exception ignored) { }
                        }
                    } else discover(code);
                } catch(Exception connectionError){
                    long now=android.os.SystemClock.elapsedRealtime();
                    if(now-lastDiscovery>20000){lastDiscovery=now;
                        discover(MediaLibrary.prefs(this).getString("remote_code",""));}
                }
                // A small command response allows launching video promptly while the app
                // is minimized. Save CPU/network work if POCO is not reachable.
                try{Thread.sleep(reachable ? 1700 : 4500);}catch(InterruptedException stop){break;}
            }
        },"MediatekaViewerRemote");
        receiverThread.setDaemon(true);receiverThread.start();
        notice("Ожидание команд от POCO");
    }
    private void discover(String code){
        if(!code.matches("[0-9]{8}"))return;
        for(String candidate:LanDiscovery.find(this))try{
            JSONObject list=new JSONObject(DownloadEngine.list(candidate,code));
            MediaLibrary.prefs(this).edit().putString("remote_host",candidate)
                    .putString("remote_cache",list.optJSONArray("files")==null?"[]":list.optJSONArray("files").toString()).commit();
            break;
        }catch(Exception ignored){}
    }
    private void act(JSONObject order){
        String action=order.optString("action","");
        Intent player=new Intent(this,RemotePlayerActivity.class)
                .putExtra("remote_action",action).putExtra("remote_id",order.optString("id",""))
                .putExtra("remote_ticket",order.optString("ticket",""));
        if("seek_to".equals(action))player.putExtra("remote_ms",order.optInt("ms",0));
        player.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if(RemotePlayerActivity.visible){
            Intent update=new Intent(RemotePlayerActivity.ACTION_PLAYER);
            update.setPackage(getPackageName());update.putExtras(player);sendBroadcast(update);
        } else if("play".equals(action)){
            if(Build.VERSION.SDK_INT<29||MainActivity.uiVisible){
                try{startActivity(player);return;}catch(Exception ignored){}
            }
            PendingIntent open=PendingIntent.getActivity(this,91,player,
                    PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
            if(Build.VERSION.SDK_INT>=26) {
                NotificationChannel ch=new NotificationChannel("mediateka_viewer_play","Запуск сериала",NotificationManager.IMPORTANCE_DEFAULT);
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            }
            Notification.Builder note=Build.VERSION.SDK_INT>=26
                    ?new Notification.Builder(this,"mediateka_viewer_play"):new Notification.Builder(this);
            note.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Запустить сериал")
                    .setContentText("POCO выбрал видео — нажми для просмотра")
                    .setContentIntent(open).setAutoCancel(true);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(88,note.build());
        }
    }
    private synchronized void stopReceiver(){
        receiverRunning=false;if(receiverThread!=null)receiverThread.interrupt();receiverThread=null;stopIfIdle();
    }
    private synchronized void startDownload(Intent intent){
        if(downloading||intent==null)return;
        String host=intent.getStringExtra("host"),code=intent.getStringExtra("code");
        String folder=intent.getStringExtra("folder"),items=intent.getStringExtra("items");
        if(host==null||code==null||folder==null||items==null)return;
        downloading=true;cancel.set(false);
        new Thread(()->{
            acquireWake();
            try {
                engine=new DownloadEngine(this,host,code,Uri.parse(folder),cancel,
                        (label,done,total)->{
                            progressText=label+(total>0?" · "+(100*done/total)+"%":"");
                            notice(progressText);
                        });
                engine.download(new JSONArray(items));
                progressText=cancel.get()?"Очередь на паузе":"Скачивание завершено";
            }catch(Exception error){progressText="Соединение прервано · очередь сохранена";}
            finally{engine=null;downloading=false;releaseWake();queueRevision++;notice(progressText);stopIfIdle();}
        },"MediatekaViewerDownload").start();
    }
    private void pause(){
        DownloadQueue.setPaused(this,true);cancel.set(true);
        DownloadEngine current=engine;if(current!=null)current.interrupt();
        progressText="Пауза · очередь сохранена";notice(progressText);
    }
    private void stopIfIdle(){if(!receiverRunning&&!downloading)stopSelf();}
    private Notification notification(String message){
        Intent intent=new Intent(this,MainActivity.class);
        PendingIntent tap=PendingIntent.getActivity(this,5,intent,
                PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Медиатека · Просмотр")
                .setContentText(message).setContentIntent(tap).setOngoing(receiverRunning||downloading);
        return b.build();
    }
    private void notice(String message){
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE,notification(message));
    }
    private void acquireWake(){
        try {PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"mediateka:viewerDownload");wake.acquire(60*60*1000L);
        }catch(Exception ignored){}
    }
    private void releaseWake(){if(wake!=null){if(wake.isHeld())wake.release();wake=null;}}
    @Override public IBinder onBind(Intent ignored){return null;}
    @Override public void onDestroy(){
        stopReceiver();cancel.set(true);DownloadEngine current=engine;
        if(current!=null)current.interrupt();releaseWake();super.onDestroy();
    }
}
