package com.serialtransfer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small viewer/controller companion. No sharing server, artwork or WebView. */
public final class MainActivity extends Activity {
    static volatile boolean uiVisible;
    private static final int BG = Color.rgb(7, 17, 26), PANEL = Color.rgb(17, 34, 45);
    private static final int TEXT = Color.rgb(235, 247, 251), MUTED = Color.rgb(142, 165, 179);
    private static final int ACCENT = Color.rgb(33, 196, 219), LINE = Color.rgb(33, 57, 72);
    private static final int PICK_SD = 33;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private volatile boolean closed, connecting, connected;
    private int page = 0, queueSeen = -1, visibleCount = 45;
    private String status = "Не подключено";
    private JSONArray files = new JSONArray();
    private String ticket = "";
    private LinearLayout body;
    private TextView stateLine, progressLine;
    private final Set<String> selected = new HashSet<>();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (closed) return;
            if (stateLine != null) stateLine.setText(status);
            if (progressLine != null) progressLine.setText(TransferService.progressText);
            if (queueSeen != TransferService.queueRevision && page == 1) {
                queueSeen = TransferService.queueRevision; draw();
            }
            handler.postDelayed(this, 1400);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        try { files = new JSONArray(prefs().getString("remote_cache", "[]")); } catch (Exception ignored) { }
        draw(); handler.post(ticker);
        if (paired()) { TransferService.send(this, TransferService.START_RECEIVER); connect(); }
    }
    @Override protected void onResume() { super.onResume(); uiVisible = true; }
    @Override protected void onPause() { uiVisible = false; super.onPause(); }
    @Override protected void onDestroy() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        network.shutdownNow();
        super.onDestroy();
    }
    private SharedPreferences prefs() { return MediaLibrary.prefs(this); }
    private boolean paired() { return prefs().getString("remote_code", "").matches("[0-9]{8}"); }
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density * n + .5f); }
    private GradientDrawable panel() {
        GradientDrawable d = new GradientDrawable(); d.setColor(PANEL); d.setCornerRadius(dp(13));
        d.setStroke(dp(1), LINE); return d;
    }
    private TextView text(String s,int size,int color,boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(color); t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private LinearLayout col() { LinearLayout b = new LinearLayout(this); b.setOrientation(1); return b; }
    private LinearLayout row() { LinearLayout b = new LinearLayout(this); b.setOrientation(0); b.setGravity(Gravity.CENTER_VERTICAL); return b; }
    private LinearLayout.LayoutParams margins(int top,int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(top),0,dp(bottom)); return p;
    }
    private Button button(String title,Runnable click) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(title); b.setTextColor(TEXT);
        b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(19, 65, 81)));
        b.setTextSize(13); b.setOnClickListener(v -> click.run()); return b;
    }
    private void action(String title,Runnable click) { body.addView(button(title,click),margins(3,5)); }
    private void title(String title,String subtitle) {
        body.addView(text(title,22,TEXT,true),margins(8,3));
        body.addView(text(subtitle,12,MUTED,false),margins(0,12));
    }
    private void card(String title,String detail) {
        LinearLayout box = col(); box.setPadding(dp(14),dp(13),dp(12),dp(13)); box.setBackground(panel());
        box.addView(text(title,14,TEXT,true)); box.addView(text(detail,12,MUTED,false),margins(5,0));
        body.addView(box,margins(0,10));
    }
    private void draw() {
        LinearLayout base = col(); base.setBackgroundColor(BG);
        LinearLayout top = row(); top.setPadding(dp(18),dp(16),dp(14),dp(12));
        LinearLayout cap = col(); cap.addView(text("▷  Медиатека",21,TEXT,true));
        cap.addView(text("Просмотр · лёгкий компаньон",11,MUTED,false));
        top.addView(cap,new LinearLayout.LayoutParams(0,-2,1));
        TextView indicator = text(connected ? "● Wi-Fi" : "○ Wi-Fi",11,connected ? ACCENT : MUTED,true);
        indicator.setOnClickListener(v -> connect()); top.addView(indicator);
        base.addView(top);
        View sep = new View(this); sep.setBackgroundColor(LINE);
        base.addView(sep,new LinearLayout.LayoutParams(-1,dp(1)));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        body = col(); body.setPadding(dp(15),dp(9),dp(15),dp(22)); scroll.addView(body);
        base.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if (page == 0) libraryPage(); else if (page == 1) downloadsPage(); else settingsPage();
        LinearLayout nav = row(); nav.setBackgroundColor(Color.rgb(10,26,37));
        String[] names={"Сериалы","Загрузки","Настройки"};
        for(int i=0;i<3;i++) {
            final int pos=i; Button b=button(names[i],()->{page=pos;draw();});
            b.setBackgroundTintList(ColorStateList.valueOf(i==page ? Color.rgb(20,77,93) : Color.rgb(10,26,37)));
            nav.addView(b,new LinearLayout.LayoutParams(0,dp(57),1));
        }
        base.addView(nav); setContentView(base);
    }
    private void libraryPage() {
        title("Мои серии", "Смотреть по Wi-Fi без интернета или скачать на microSD");
        stateLine = text(status,13,ACCENT,false); body.addView(stateLine,margins(1,7));
        LinearLayout controls=row();
        controls.addView(button("Обновить",this::connect),new LinearLayout.LayoutParams(0,dp(46),1));
        controls.addView(button("Подключение",this::pairDialog),new LinearLayout.LayoutParams(0,dp(46),1));
        body.addView(controls,margins(0,10));
        JSONObject resume=WatchHistory.last(this);
        if(resume!=null && !resume.optBoolean("finished")) {
            String id=resume.optString("id","");
            card("Продолжить просмотр",resume.optString("name","Серия")+"  ·  "+(resume.optInt("position")/60000)+" мин");
            action("▶ Продолжить",()->play(id));
        }
        if(files.length()==0) {
            card("Нет серий", paired()?"Нажми «Обновить». Убедись, что POCO раздаёт библиотеку.":"Один раз введи IP и код с POCO.");
            return;
        }
        Set<String> queued = DownloadQueue.queuedIds(this);
        int count=Math.min(files.length(),visibleCount);
        for(int i=0;i<count;i++) {
            JSONObject episode=files.optJSONObject(i); if(episode==null)continue;
            String id=episode.optString("id","");
            LinearLayout panel=col(); panel.setPadding(dp(8),dp(8),dp(8),dp(8)); panel.setBackground(panel());
            LinearLayout line=row(); CheckBox tick=new CheckBox(this); tick.setButtonTintList(ColorStateList.valueOf(ACCENT));
            tick.setChecked(selected.contains(id)); tick.setOnCheckedChangeListener((b,checked)->{
                if(checked)selected.add(id); else selected.remove(id);
            }); line.addView(tick);
            LinearLayout middle=col();
            TextView name=text(episode.optString("name","Серия"),13,TEXT,true);name.setMaxLines(2);
            middle.addView(name);
            String state=DownloadQueue.status(this,id);
            String suffix=DownloadQueue.DONE.equals(state)?"Скачано":queued.contains(id)?"В очереди":"На POCO";
            middle.addView(text(MediaLibrary.readableSize(episode.optLong("size",-1))+" · "+suffix,11,MUTED,false));
            line.addView(middle,new LinearLayout.LayoutParams(0,-2,1));
            line.addView(button("▶",()->play(id)),new LinearLayout.LayoutParams(dp(54),dp(45)));
            panel.addView(line); body.addView(panel,margins(0,7));
        }
        if(files.length()>count)action("Показать ещё · "+(files.length()-count),()->{
            visibleCount+=45;draw();
        });
        action("↓ Скачать отмеченные",()->queue(false));
        action("↓ Скачать недостающие",()->queue(true));
    }
    private void downloadsPage() {
        title("Загрузки", "Очередь сохраняется при отключении Wi-Fi");
        String folder=prefs().getString(MediaLibrary.DEST_TREE,"");
        card("Куда сохраняются серии",folder.isEmpty()?"Папка ещё не выбрана":"Папка microSD выбрана");
        action("Выбрать папку microSD",()->{
            Intent open=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(open,PICK_SD);
        });
        card("В очереди: "+DownloadQueue.waiting(this),DownloadQueue.paused(this)?"Пауза":"Ожидают загрузки или продолжаются");
        progressLine=text(TransferService.progressText,13,ACCENT,false);body.addView(progressLine,margins(4,9));
        action("▶ Продолжить очередь",()->{DownloadQueue.setPaused(this,false);autoResumeQueue();});
        action("Пауза",()->TransferService.send(this,TransferService.PAUSE));
    }
    private void settingsPage() {
        title("Настройки", "Один раз настроил — дальше подключается само");
        String host=prefs().getString("remote_host","");
        card("Телефон POCO",host.isEmpty()?"Не сопряжён":"Сохранённый IP: "+host);
        action("Изменить IP и код",this::pairDialog);
        action("Найти POCO",this::connect);
        preferenceToggle("auto_next_episode_v7","Автоматически следующая серия",true);
        preferenceToggle("prefer_vlc_v7","Использовать VLC по умолчанию",false);
        preferenceToggle("remember_volume_v7","Запоминать громкость",false);
        card("Для Android 7", "Просмотр и передача работают без интернета, когда оба устройства находятся в одной сети Wi-Fi.");
    }
    private void preferenceToggle(String key,String label,boolean def) {
        CheckBox box=new CheckBox(this);box.setText(label);box.setTextColor(TEXT);box.setButtonTintList(ColorStateList.valueOf(ACCENT));
        box.setChecked(prefs().getBoolean(key,def));box.setOnCheckedChangeListener((b,enabled)->prefs().edit().putBoolean(key,enabled).apply());
        body.addView(box,margins(7,5));
    }
    private void pairDialog() {
        LinearLayout wrapper=col();wrapper.setPadding(dp(22),dp(10),dp(22),0);
        EditText host=new EditText(this);host.setSingleLine(true);host.setText(prefs().getString("remote_host",""));
        host.setHint("IP POCO (можно оставить пустым)");host.setTextColor(TEXT);host.setHintTextColor(MUTED);
        host.setInputType(InputType.TYPE_CLASS_TEXT);
        EditText code=new EditText(this);code.setSingleLine(true);code.setHint("Код из 8 цифр");
        code.setText(prefs().getString("remote_code",""));code.setTextColor(TEXT);code.setHintTextColor(MUTED);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);wrapper.addView(host);wrapper.addView(code);
        new AlertDialog.Builder(this).setTitle("Подключение к POCO").setView(wrapper)
                .setNegativeButton("Отмена",null).setPositiveButton("Сохранить",(d,which)->{
                    String secret=code.getText().toString().trim();
                    if(!secret.matches("[0-9]{8}")){toast("Нужен восьмизначный код");return;}
                    String ip=host.getText().toString().trim();
                    try { if(!ip.isEmpty())ip=DownloadEngine.validatedHost(ip); }
                    catch(Exception e){toast("Неверный IP");return;}
                    prefs().edit().putString("remote_host",ip).putString("remote_code",secret).commit();
                    TransferService.send(this,TransferService.START_RECEIVER);
                    connect();
                }).show();
    }
    private void connect() {
        if(connecting || closed)return;
        if(!paired()){pairDialog();return;}
        connecting=true;status="Подключаюсь к POCO…";
        network.execute(()->{
            String secret=prefs().getString("remote_code","");
            String current=prefs().getString("remote_host","");
            List<String> candidates=new ArrayList<>();
            if(!current.isEmpty())candidates.add(current);
            for(String ip:LanDiscovery.find(this))if(!candidates.contains(ip))candidates.add(ip);
            JSONObject listing=null;String found="";String error="Телефон не найден в Wi-Fi";
            for(String ip:candidates) {
                try {listing=new JSONObject(DownloadEngine.list(ip,secret));found=ip;break;}
                catch(Exception e){error=e.getMessage()==null?error:e.getMessage();}
            }
            final String resolved=found, reason=error;final JSONObject data=listing;
            if(data!=null) {
                JSONArray a=data.optJSONArray("files");
                if(a==null)a=new JSONArray();
                final JSONArray catalog=a;
                final String stream=data.optString("stream_ticket","");
                prefs().edit().putString("remote_host",resolved).putString("remote_cache",catalog.toString()).commit();
                String previous=current;
                try { DownloadQueue.reconcile(this,catalog,previous,resolved); }
                catch(Exception ignored){}
                handler.post(()->{
                    if(closed)return;connected=true;status="● POCO подключён · "+catalog.length()+" серий";
                    files=catalog;ticket=stream;connecting=false;draw();autoResumeQueue();
                });
            } else handler.post(()->{
                if(closed)return;connecting=false;connected=false;status="Нет соединения: "+reason;draw();
            });
        });
    }
    private void play(String id) {
        if(!id.matches("[0-9a-f]{24}")){toast("Серия недоступна");return;}
        if(!connected || !ticket.matches("[0-9a-f]{48}")) {
            if(DownloadQueue.DONE.equals(DownloadQueue.status(this,id))) {
                String folder=prefs().getString(MediaLibrary.DEST_TREE,"");
                if(!folder.isEmpty())try {
                    Uri uri=DownloadQueue.knownFile(this,id,Uri.parse(folder),
                            prefs().getString("remote_host",""),prefs().getString("remote_host",""));
                    if(uri!=null){
                        Intent watch=new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri,"video/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(watch,"Воспроизвести офлайн"));
                        return;
                    }
                } catch(Exception ignored) { }
            }
            toast("POCO не подключён; скачанные серии можно открыть офлайн");return;
        }
        Intent in=new Intent(this,RemotePlayerActivity.class).putExtra("remote_action","play")
                .putExtra("remote_id",id).putExtra("remote_ticket",ticket);
        startActivity(in);
    }
    private void queue(boolean missing) {
        if(!connected){toast("Сначала подключись к POCO");return;}
        if(prefs().getString(MediaLibrary.DEST_TREE,"").isEmpty()){page=1;draw();toast("Сначала выбери microSD");return;}
        JSONArray chosen=new JSONArray();
        for(int i=0;i<files.length();i++) {
            JSONObject entry=files.optJSONObject(i);if(entry==null)continue;
            String id=entry.optString("id","");
            if(DownloadQueue.DONE.equals(DownloadQueue.status(this,id)))continue;
            if(missing||selected.contains(id))chosen.put(entry);
        }
        int count=DownloadQueue.enqueue(this,chosen);selected.clear();
        toast(count>0?"Добавлено: "+count:"Новых серий нет");
        page=1;draw();autoResumeQueue();
    }
    private void autoResumeQueue() {
        if(!connected||DownloadQueue.paused(this)||TransferService.downloading)return;
        if(prefs().getString(MediaLibrary.DEST_TREE,"").isEmpty())return;
        JSONArray pending=DownloadQueue.availablePending(this,files);
        if(pending.length()==0)return;
        TransferService.download(this,prefs().getString("remote_host",""),prefs().getString("remote_code",""),
                prefs().getString(MediaLibrary.DEST_TREE,""),pending);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK_SD||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri tree=data.getData();
        try {getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            String old=prefs().getString(MediaLibrary.DEST_TREE,"");
            if(!tree.toString().equals(old)){
                prefs().edit().putString(MediaLibrary.DEST_TREE,tree.toString()).commit();
                DownloadQueue.destinationChanged(this);
            }
            draw();autoResumeQueue();
        }catch(Exception e){toast("Нет доступа к microSD: "+e.getMessage());}
    }
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
}
