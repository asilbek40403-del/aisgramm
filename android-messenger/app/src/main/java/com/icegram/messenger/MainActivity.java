package com.icegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int BG = Color.rgb(11, 18, 32);
    private static final int PANEL = Color.rgb(17, 24, 39);
    private static final int PANEL_2 = Color.rgb(30, 41, 59);
    private static final int BLUE = Color.rgb(42, 171, 238);
    private static final int TEXT = Color.rgb(248, 250, 252);
    private static final int MUTED = Color.rgb(148, 163, 184);
    private static final int DANGER = Color.rgb(239, 68, 68);

    private FrameLayout root;
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Session session;
    private Api api;

    private String screen = "auth";
    private String currentChatId;
    private String currentChatTitle;
    private boolean polling;
    private boolean messagesLoading;
    private String messageFingerprint = "";
    private Runnable pollRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(PANEL);
        w.setNavigationBarColor(BG);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        session = new Session(this);
        api = new Api(session);

        if (session.hasSession()) {
            showChats();
        } else {
            showAuth();
        }
    }

    private void showAuth() {
        stopPolling();
        screen = "auth";
        currentChatId = null;

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = page();
        page.setPadding(dp(24), dp(34), dp(24), dp(34));
        scroll.addView(page);

        TextView logo = text("I", 32, TEXT, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(BLUE, 28));
        page.addView(logo, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView title = text("Icegram", 30, TEXT, true);
        LinearLayout.LayoutParams titleLp = lpMatchWrap();
        titleLp.topMargin = dp(18);
        page.addView(title, titleLp);
        page.addView(text("Базовый мессенджер: профиль, чаты и сообщения", 14, MUTED, false), lpMatchWrap());

        EditText email = field("Email", false, 1);
        EditText password = field("Пароль", true, 1);
        EditText username = field("Username для регистрации", false, 1);
        EditText displayName = field("Имя для регистрации", false, 1);

        page.addView(email, top(dp(28)));
        page.addView(password, top(dp(12)));
        page.addView(username, top(dp(12)));
        page.addView(displayName, top(dp(12)));

        Button login = primary("Войти");
        Button register = secondary("Создать аккаунт");
        page.addView(login, top(dp(18)));
        page.addView(register, top(dp(10)));

        TextView hint = text("При регистрации username: 3–32 символа, только a-z, 0-9 и _.", 12, MUTED, false);
        page.addView(hint, top(dp(14)));

        login.setOnClickListener(v -> {
            String e = email.getText().toString().trim();
            String p = password.getText().toString();
            if (e.isEmpty() || p.isEmpty()) {
                toast("Введи email и пароль");
                return;
            }
            setButtonBusy(login, true, "Входим…");
            task(() -> {
                AuthResult ar = api.signIn(e, p);
                session.save(ar);
                api.ensureProfile(null, null);
                return true;
            }, ok -> {
                setButtonBusy(login, false, "Войти");
                showChats();
            }, err -> {
                setButtonBusy(login, false, "Войти");
                showError(err);
            });
        });

        register.setOnClickListener(v -> {
            String e = email.getText().toString().trim();
            String p = password.getText().toString();
            String u = username.getText().toString().trim().toLowerCase(Locale.ROOT);
            String n = displayName.getText().toString().trim();

            if (e.isEmpty() || p.length() < 6 || !Api.USERNAME.matcher(u).matches() || n.isEmpty()) {
                toast("Проверь email, пароль (минимум 6), username и имя");
                return;
            }

            setButtonBusy(register, true, "Создаём…");
            task(() -> api.signUp(e, p, u, n), ar -> {
                setButtonBusy(register, false, "Создать аккаунт");
                if (ar.hasSession()) {
                    session.save(ar);
                    task(() -> {
                        api.ensureProfile(u, n);
                        return true;
                    }, ok -> showChats(), this::showError);
                } else {
                    toast("Аккаунт создан. Если требуется подтверждение email — подтверди его и нажми «Войти».");
                }
            }, err -> {
                setButtonBusy(register, false, "Создать аккаунт");
                showError(err);
            });
        });

        setScreen(scroll);
    }

    private void showChats() {
        stopPolling();
        screen = "chats";
        currentChatId = null;
        currentChatTitle = null;

        LinearLayout outer = page();
        outer.setPadding(dp(16), dp(18), dp(16), dp(22));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Icegram", 26, TEXT, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button profile = compactButton("Профиль");
        top.addView(profile);
        outer.addView(top);

        Button newChat = primary("＋ Новый чат");
        outer.addView(newChat, top(dp(18)));

        TextView section = text("Чаты", 18, TEXT, true);
        outer.addView(section, top(dp(22)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = page();
        scroll.addView(list);
        outer.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        profile.setOnClickListener(v -> showProfile());
        newChat.setOnClickListener(v -> showFindUsers());

        setScreen(outer);
        loadChats(list);
    }

    private void loadChats(LinearLayout list) {
        list.removeAllViews();
        TextView loading = text("Загружаю чаты…", 14, MUTED, false);
        list.addView(loading, top(dp(12)));

        task(api::myChats, arr -> {
            if (!"chats".equals(screen)) return;
            list.removeAllViews();
            if (arr.length() == 0) {
                TextView empty = text("Пока нет чатов. Нажми «Новый чат» и выбери пользователя.", 14, MUTED, false);
                empty.setPadding(dp(16), dp(18), dp(16), dp(18));
                empty.setBackground(round(PANEL, 18));
                list.addView(empty, top(dp(12)));
                return;
            }

            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                String chatId = c.optString("chat_id");
                String name = c.optString("display_name", "Пользователь");
                String username = c.optString("username", "");
                String last = c.optString("last_body", "");
                if (last.isEmpty()) last = "Сообщений пока нет";
                list.addView(chatRow(name, username, last, () -> showChat(chatId, name)), top(dp(10)));
            }
        }, this::showError);
    }

    private void showFindUsers() {
        stopPolling();
        screen = "users";

        LinearLayout outer = page();
        outer.setPadding(dp(16), dp(16), dp(16), dp(20));
        outer.addView(header("Новый чат", this::showChats));

        EditText search = field("Поиск по имени или username", false, 1);
        Button searchBtn = primary("Найти");
        outer.addView(search, top(dp(16)));
        outer.addView(searchBtn, top(dp(10)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout results = page();
        scroll.addView(results);
        outer.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setScreen(outer);

        Runnable doSearch = () -> {
            String q = search.getText().toString().trim();
            results.removeAllViews();
            results.addView(text("Ищу…", 14, MUTED, false), top(dp(12)));
            task(() -> api.listUsers(q), users -> {
                if (!"users".equals(screen)) return;
                results.removeAllViews();
                if (users.length() == 0) {
                    results.addView(text("Пользователи не найдены.", 14, MUTED, false), top(dp(14)));
                    return;
                }
                for (int i = 0; i < users.length(); i++) {
                    JSONObject u = users.optJSONObject(i);
                    if (u == null) continue;
                    String uid = u.optString("id");
                    String name = u.optString("display_name", "Пользователь");
                    String uname = u.optString("username", "");
                    String bio = u.optString("bio", "");
                    View row = userRow(name, uname, bio, () -> {
                        toast("Открываю чат…");
                        task(() -> api.openDirectChat(uid), chatId -> showChat(chatId, name), this::showError);
                    });
                    results.addView(row, top(dp(10)));
                }
            }, this::showError);
        };

        searchBtn.setOnClickListener(v -> doSearch.run());
        doSearch.run();
    }

    private void showProfile() {
        stopPolling();
        screen = "profile";

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = page();
        page.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(page);

        page.addView(header("Профиль", this::showChats));

        EditText username = field("Username", false, 1);
        EditText name = field("Имя", false, 1);
        EditText bio = field("О себе", false, 4);
        Button save = primary("Сохранить изменения");
        Button logout = dangerButton("Выйти из аккаунта");

        page.addView(username, top(dp(20)));
        page.addView(name, top(dp(10)));
        page.addView(bio, top(dp(10)));
        page.addView(save, top(dp(16)));
        page.addView(logout, top(dp(10)));

        TextView status = text("Загружаю профиль…", 13, MUTED, false);
        page.addView(status, top(dp(12)));

        setScreen(scroll);

        task(api::myProfile, p -> {
            if (!"profile".equals(screen)) return;
            username.setText(p.optString("username", ""));
            name.setText(p.optString("display_name", ""));
            bio.setText(p.optString("bio", ""));
            status.setText(session.email());
        }, this::showError);

        save.setOnClickListener(v -> {
            String u = username.getText().toString().trim().toLowerCase(Locale.ROOT);
            String n = name.getText().toString().trim();
            String b = bio.getText().toString().trim();
            if (!Api.USERNAME.matcher(u).matches() || n.isEmpty()) {
                toast("Username: 3–32 символа a-z, 0-9, _. Имя не должно быть пустым.");
                return;
            }
            setButtonBusy(save, true, "Сохраняю…");
            task(() -> {
                api.updateProfile(u, n, b);
                return true;
            }, ok -> {
                setButtonBusy(save, false, "Сохранить изменения");
                toast("Профиль сохранён");
            }, err -> {
                setButtonBusy(save, false, "Сохранить изменения");
                showError(err);
            });
        });

        logout.setOnClickListener(v -> {
            session.clear();
            showAuth();
        });
    }

    private void showChat(String chatId, String title) {
        stopPolling();
        screen = "chat";
        currentChatId = chatId;
        currentChatTitle = title;
        messageFingerprint = "";

        LinearLayout outer = page();
        outer.setPadding(dp(12), dp(12), dp(12), dp(12));
        outer.addView(header(title, this::showChats));

        ScrollView messageScroll = new ScrollView(this);
        LinearLayout messages = page();
        messages.setPadding(dp(2), dp(12), dp(2), dp(12));
        messageScroll.addView(messages);
        outer.addView(messageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout compose = new LinearLayout(this);
        compose.setOrientation(LinearLayout.HORIZONTAL);
        compose.setGravity(Gravity.BOTTOM);

        EditText input = field("Сообщение", false, 3);
        input.setSingleLine(false);
        input.setMaxLines(4);
        compose.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button send = compactButton("Отправить");
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        sendLp.leftMargin = dp(8);
        compose.addView(send, sendLp);
        outer.addView(compose, top(dp(8)));

        send.setOnClickListener(v -> {
            String body = input.getText().toString().trim();
            if (body.isEmpty()) return;
            setButtonBusy(send, true, "…");
            task(() -> {
                api.sendMessage(chatId, body);
                return true;
            }, ok -> {
                input.setText("");
                setButtonBusy(send, false, "Отправить");
                loadMessages(chatId, messages, messageScroll, true);
            }, err -> {
                setButtonBusy(send, false, "Отправить");
                showError(err);
            });
        });

        setScreen(outer);
        polling = true;
        loadMessages(chatId, messages, messageScroll, true);
        startPolling(chatId, messages, messageScroll);
    }

    private void startPolling(String chatId, LinearLayout box, ScrollView scroll) {
        polling = true;
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!polling || !"chat".equals(screen) || !chatId.equals(currentChatId)) return;
                loadMessages(chatId, box, scroll, false);
                handler.postDelayed(this, 1400);
            }
        };
        handler.postDelayed(pollRunnable, 1400);
    }

    private void stopPolling() {
        polling = false;
        messagesLoading = false;
        if (pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    private void loadMessages(String chatId, LinearLayout box, ScrollView scroll, boolean force) {
        if (messagesLoading) return;
        messagesLoading = true;

        task(() -> api.messages(chatId), arr -> {
            messagesLoading = false;
            if (!"chat".equals(screen) || !chatId.equals(currentChatId)) return;

            String lastId = arr.length() == 0 ? "" : arr.optJSONObject(arr.length() - 1).optString("id");
            String fingerprint = arr.length() + ":" + lastId;
            if (!force && fingerprint.equals(messageFingerprint)) return;
            messageFingerprint = fingerprint;

            box.removeAllViews();
            if (arr.length() == 0) {
                TextView empty = text("Напиши первое сообщение", 14, MUTED, false);
                empty.setGravity(Gravity.CENTER);
                box.addView(empty, top(dp(24)));
            } else {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    boolean mine = session.userId().equals(m.optString("sender_id"));
                    String body = m.optString("body", "");
                    String time = shortTime(m.optString("created_at", ""));
                    box.addView(messageBubble(body, time, mine), top(dp(6)));
                }
            }
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }, err -> {
            messagesLoading = false;
            showError(err);
        });
    }

    private View chatRow(String name, String username, String last, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(round(PANEL, 18));

        TextView avatar = avatar(name);
        row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout info = page();
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(12);
        row.addView(info, infoLp);

        info.addView(text(name, 16, TEXT, true));
        String sub = (username.isEmpty() ? "" : "@" + username + " · ") + last;
        TextView s = text(sub, 13, MUTED, false);
        s.setSingleLine(true);
        info.addView(s, top(dp(3)));

        row.setOnClickListener(v -> click.run());
        return row;
    }

    private View userRow(String name, String username, String bio, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(round(PANEL, 18));

        row.addView(avatar(name), new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout info = page();
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(12);
        row.addView(info, infoLp);
        info.addView(text(name, 16, TEXT, true));
        info.addView(text("@" + username + (bio.isEmpty() ? "" : " · " + bio), 13, MUTED, false), top(dp(3)));

        Button open = compactButton("Чат");
        row.addView(open);
        row.setOnClickListener(v -> click.run());
        open.setOnClickListener(v -> click.run());
        return row;
    }

    private View messageBubble(String body, String time, boolean mine) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(mine ? Gravity.END : Gravity.START);

        TextView bubble = text(body + (time.isEmpty() ? "" : "\n" + time), 15, TEXT, false);
        bubble.setPadding(dp(13), dp(9), dp(13), dp(9));
        bubble.setMaxWidth(dp(310));
        bubble.setBackground(round(mine ? BLUE : PANEL_2, 18));
        row.addView(bubble);
        return row;
    }

    private LinearLayout header(String title, Runnable back) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(2), 0, dp(8));

        Button backBtn = compactButton("‹");
        backBtn.setTextSize(28);
        backBtn.setMinWidth(dp(48));
        backBtn.setOnClickListener(v -> back.run());
        bar.addView(backBtn, new LinearLayout.LayoutParams(dp(50), dp(48)));

        TextView t = text(title, 20, TEXT, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(8);
        bar.addView(t, lp);
        return bar;
    }

    private LinearLayout page() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(BG);
        return l;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView avatar(String name) {
        String s = name == null || name.trim().isEmpty() ? "?" : name.trim().substring(0, 1).toUpperCase(Locale.ROOT);
        TextView v = text(s, 20, TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(BLUE, 24));
        return v;
    }

    private EditText field(String hint, boolean password, int lines) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(15);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackground(round(PANEL, 14));
        e.setMinHeight(dp(52));
        e.setSingleLine(lines == 1);
        e.setMaxLines(lines);
        if (password) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | (lines > 1 ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        }
        return e;
    }

    private Button primary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackgroundTintList(ColorStateList.valueOf(BLUE));
        b.setMinHeight(dp(52));
        return b;
    }

    private Button secondary(String label) {
        Button b = primary(label);
        b.setBackgroundTintList(ColorStateList.valueOf(PANEL_2));
        return b;
    }

    private Button dangerButton(String label) {
        Button b = primary(label);
        b.setBackgroundTintList(ColorStateList.valueOf(DANGER));
        return b;
    }

    private Button compactButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackgroundTintList(ColorStateList.valueOf(PANEL_2));
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams lp = lpMatchWrap();
        lp.topMargin = margin;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setScreen(View v) {
        root.removeAllViews();
        root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void setButtonBusy(Button b, boolean busy, String label) {
        b.setEnabled(!busy);
        b.setText(label);
        b.setAlpha(busy ? 0.65f : 1f);
    }

    private <T> void task(Callable<T> work, Consumer<T> ok, Consumer<Exception> fail) {
        io.submit(() -> {
            try {
                T result = work.call();
                runOnUiThread(() -> ok.accept(result));
            } catch (Exception e) {
                runOnUiThread(() -> fail.accept(e));
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void showError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = "Неизвестная ошибка";
        toast(m);
    }

    private String shortTime(String iso) {
        if (iso == null) return "";
        int t = iso.indexOf('T');
        if (t >= 0 && iso.length() >= t + 6) return iso.substring(t + 1, t + 6);
        return "";
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if ("chat".equals(screen) || "users".equals(screen) || "profile".equals(screen)) {
            showChats();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        io.shutdownNow();
        super.onDestroy();
    }

    static class Session {
        private final SharedPreferences p;

        Session(Context c) {
            p = c.getSharedPreferences("icegram_session", Context.MODE_PRIVATE);
        }

        boolean hasSession() {
            return !access().isEmpty() && !userId().isEmpty();
        }

        String access() { return p.getString("access", ""); }
        String refresh() { return p.getString("refresh", ""); }
        String userId() { return p.getString("uid", ""); }
        String email() { return p.getString("email", ""); }

        void save(AuthResult a) {
            SharedPreferences.Editor e = p.edit();
            if (a.access != null && !a.access.isEmpty()) e.putString("access", a.access);
            if (a.refresh != null && !a.refresh.isEmpty()) e.putString("refresh", a.refresh);
            if (a.userId != null && !a.userId.isEmpty()) e.putString("uid", a.userId);
            if (a.email != null && !a.email.isEmpty()) e.putString("email", a.email);
            e.apply();
        }

        void updateTokens(String access, String refresh) {
            SharedPreferences.Editor e = p.edit().putString("access", access);
            if (refresh != null && !refresh.isEmpty()) e.putString("refresh", refresh);
            e.apply();
        }

        void clear() {
            p.edit().clear().apply();
        }
    }

    static class AuthResult {
        String access = "";
        String refresh = "";
        String userId = "";
        String email = "";

        boolean hasSession() {
            return !access.isEmpty() && !userId.isEmpty();
        }

        static AuthResult from(JSONObject j) {
            AuthResult r = new AuthResult();
            r.access = j.optString("access_token", "");
            r.refresh = j.optString("refresh_token", "");
            JSONObject u = j.optJSONObject("user");
            if (u != null) {
                r.userId = u.optString("id", "");
                r.email = u.optString("email", "");
            }
            return r;
        }
    }

    static class ApiException extends Exception {
        final int code;
        ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    static class Api {
        static final String BASE = "https://pjhgjqbpnizdagsfksmn.supabase.co";
        static final String KEY = "sb_publishable_yttqGpAA2Nnm0ANr3urq0g_wbDKF1q1";
        static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,32}$");

        private final Session session;

        Api(Session session) {
            this.session = session;
        }

        AuthResult signIn(String email, String password) throws Exception {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("password", password);
            JSONObject j = new JSONObject(request("POST", "/auth/v1/token?grant_type=password", body, false, null, false));
            AuthResult r = AuthResult.from(j);
            if (!r.hasSession()) throw new Exception("Не удалось войти");
            return r;
        }

        AuthResult signUp(String email, String password, String username, String displayName) throws Exception {
            JSONObject data = new JSONObject();
            data.put("username", username);
            data.put("display_name", displayName);

            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("password", password);
            body.put("data", data);

            JSONObject j = new JSONObject(request("POST", "/auth/v1/signup", body, false, null, false));
            AuthResult r = AuthResult.from(j);
            if (r.email.isEmpty()) {
                JSONObject u = j.optJSONObject("user");
                if (u != null) r.email = u.optString("email", email);
            }
            return r;
        }

        void ensureProfile(String requestedUsername, String requestedName) throws Exception {
            if (!session.hasSession()) return;
            JSONArray existing = new JSONArray(request(
                    "GET",
                    "/rest/v1/icegram_profiles?select=id&id=eq." + session.userId() + "&limit=1",
                    null, true, null, true));
            if (existing.length() > 0) return;

            String username = requestedUsername == null ? "" : requestedUsername.trim().toLowerCase(Locale.ROOT);
            if (!USERNAME.matcher(username).matches()) username = generatedUsername();

            String name = requestedName == null ? "" : requestedName.trim();
            if (name.isEmpty()) {
                String e = session.email();
                name = e.contains("@") ? e.substring(0, e.indexOf('@')) : "Icegram user";
            }

            JSONObject body = new JSONObject();
            body.put("id", session.userId());
            body.put("username", username);
            body.put("display_name", name);
            body.put("bio", "");

            try {
                request("POST", "/rest/v1/icegram_profiles", body, true, "return=minimal", true);
            } catch (ApiException e) {
                if (e.code != 409) throw e;
                body.put("username", generatedUsername());
                request("POST", "/rest/v1/icegram_profiles", body, true, "return=minimal", true);
            }
        }

        JSONObject myProfile() throws Exception {
            ensureProfile(null, null);
            JSONArray a = new JSONArray(request(
                    "GET",
                    "/rest/v1/icegram_profiles?select=id,username,display_name,bio,avatar_path&id=eq." + session.userId() + "&limit=1",
                    null, true, null, true));
            if (a.length() == 0) throw new Exception("Профиль не найден");
            return a.getJSONObject(0);
        }

        void updateProfile(String username, String displayName, String bio) throws Exception {
            if (!USERNAME.matcher(username).matches()) throw new Exception("Неверный username");
            JSONObject body = new JSONObject();
            body.put("username", username);
            body.put("display_name", displayName);
            body.put("bio", bio);
            request("PATCH",
                    "/rest/v1/icegram_profiles?id=eq." + session.userId(),
                    body, true, "return=minimal", true);
        }

        JSONArray listUsers(String q) throws Exception {
            JSONArray all = new JSONArray(request(
                    "GET",
                    "/rest/v1/icegram_profiles?select=id,username,display_name,bio&order=display_name.asc&limit=200",
                    null, true, null, true));

            JSONArray out = new JSONArray();
            String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < all.length(); i++) {
                JSONObject u = all.optJSONObject(i);
                if (u == null) continue;
                if (session.userId().equals(u.optString("id"))) continue;
                String hay = (u.optString("username") + " " + u.optString("display_name")).toLowerCase(Locale.ROOT);
                if (needle.isEmpty() || hay.contains(needle)) out.put(u);
            }
            return out;
        }

        JSONArray myChats() throws Exception {
            return new JSONArray(request(
                    "POST", "/rest/v1/rpc/icegram_my_direct_chats",
                    new JSONObject(), true, null, true));
        }

        String openDirectChat(String otherUser) throws Exception {
            JSONObject body = new JSONObject();
            body.put("p_other_user", otherUser);
            String s = request("POST", "/rest/v1/rpc/icegram_open_direct_chat", body, true, null, true);
            if (s.trim().startsWith("[")) {
                JSONArray a = new JSONArray(s);
                if (a.length() > 0) return a.getJSONObject(0).optString("chat_id");
            } else {
                JSONObject o = new JSONObject(s);
                String id = o.optString("chat_id");
                if (!id.isEmpty()) return id;
            }
            throw new Exception("Не удалось открыть чат");
        }

        JSONArray messages(String chatId) throws Exception {
            return new JSONArray(request(
                    "GET",
                    "/rest/v1/icegram_messages?select=id,chat_id,sender_id,kind,body,created_at,edited_at&chat_id=eq."
                            + chatId + "&order=created_at.asc&limit=300",
                    null, true, null, true));
        }

        void sendMessage(String chatId, String bodyText) throws Exception {
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("sender_id", session.userId());
            body.put("kind", "text");
            body.put("body", bodyText);
            request("POST", "/rest/v1/icegram_messages", body, true, "return=minimal", true);
        }

        private String generatedUsername() {
            String e = session.email().toLowerCase(Locale.ROOT);
            String base = e.contains("@") ? e.substring(0, e.indexOf('@')) : "user";
            base = base.replaceAll("[^a-z0-9_]", "_");
            if (base.length() > 20) base = base.substring(0, 20);
            if (base.length() < 3) base = "user";
            String uid = session.userId().replace("-", "");
            String suffix = uid.length() >= 6 ? uid.substring(0, 6) : uid;
            return base + "_" + suffix;
        }

        private boolean refreshSession() {
            try {
                String rt = session.refresh();
                if (rt.isEmpty()) return false;
                JSONObject body = new JSONObject();
                body.put("refresh_token", rt);
                JSONObject j = new JSONObject(request(
                        "POST", "/auth/v1/token?grant_type=refresh_token",
                        body, false, null, false));
                String access = j.optString("access_token", "");
                String refresh = j.optString("refresh_token", rt);
                if (access.isEmpty()) return false;
                session.updateTokens(access, refresh);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private String request(String method, String path, JSONObject body, boolean auth, String prefer, boolean canRefresh) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("apikey", KEY);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", "application/json");
            if (auth) c.setRequestProperty("Authorization", "Bearer " + session.access());
            if (prefer != null) c.setRequestProperty("Prefer", prefer);

            if (body != null) {
                c.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
            }

            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            String response = read(in);

            if (code == 401 && auth && canRefresh && refreshSession()) {
                return request(method, path, body, true, prefer, false);
            }

            if (code < 200 || code >= 300) {
                throw new ApiException(code, errorMessage(code, response));
            }
            return response == null || response.trim().isEmpty() ? "[]" : response;
        }

        private String read(InputStream in) throws Exception {
            if (in == null) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        }

        private String errorMessage(int code, String raw) {
            try {
                JSONObject j = new JSONObject(raw);
                String m = j.optString("message", "");
                if (m.isEmpty()) m = j.optString("msg", "");
                if (m.isEmpty()) m = j.optString("error_description", "");
                if (m.isEmpty()) m = j.optString("error", "");
                if (!m.isEmpty()) return m;
            } catch (Exception ignored) {
            }
            return "Ошибка сервера (" + code + ")";
        }
    }
}
