package ph.bananatoss.game;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The whole game: a night skyline, two gorillas on rooftops, and exploding bananas.
 * Players take turns setting an angle and a speed; gravity and wind bend the throw.
 */
public class GameView extends View {

    // ---------------- game settings ----------------
    private static final String[] GRAVITY_NAMES = {"Earth", "Moon", "Mars", "Jupiter"};
    private static final float[] GRAVITY_VALUES = {9.8f, 1.6f, 3.7f, 24.8f};
    private static final int[] WIN_TARGETS = {3, 5, 10};

    private enum State { MENU, AIM, FLY, EXPLODE, DANCE, OVER }

    private State state = State.MENU;
    private boolean vsCpu = true;
    private int gravityIdx = 0, winIdx = 0, cpuLevel = 1; // 0 easy, 1 normal, 2 hard

    // ---------------- world ----------------
    private int W, H;
    private float unit;            // pixels per physics unit (640 units across the screen)
    private Bitmap city;           // buildings; exploded holes are transparent
    private Canvas cityCanvas;
    private final List<RectF> buildings = new ArrayList<>();
    private final float[] gx = new float[2], gFoot = new float[2];
    private float gh;              // gorilla height in pixels
    private float wind;            // -10 .. 10
    private final Random rnd = new Random();

    // ---------------- turn state ----------------
    private int turn = 0;          // 0 = left player, 1 = right player
    private final int[] score = new int[2];
    private final float[] lastAngle = {45, 45}, lastSpeed = {50, 50};
    private boolean hasLast0, hasLast1;
    private float aimAngle = 45, aimSpeed = 50;
    private boolean aiming;
    private float downX, downY;

    // banana flight
    private float bx0, by0, bvx, bvy, bt, bx, by, spin;
    private boolean bananaVisible;
    private int thrower;
    private boolean sunShocked;

    // explosions and dance
    private final List<float[]> blasts = new ArrayList<>(); // x, y, maxRadius, age, life
    private float stateTime;
    private int loser = -1, winner = -1;
    private boolean[] alive = {true, true};
    private String message = "";

    // CPU
    private float cpuError;
    private float cpuDelay;

    private long lastFrame;
    private ToneGenerator tone;

    // ---------------- paints ----------------
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint sky;
    private final Path path = new Path();

    // menu buttons
    private final RectF bMode = new RectF(), bGravity = new RectF(), bWin = new RectF(), bLevel = new RectF(),
            bStart = new RectF(), bAgain = new RectF(), bMenu = new RectF(), bQuit = new RectF();

    // aiming panel: angle -5 -1 [value] +1 +5, speed -5 -1 [value] +1 +5, Type, THROW
    private static final int A_M5 = 0, A_M1 = 1, A_VAL = 2, A_P1 = 3, A_P5 = 4,
            S_M5 = 5, S_M1 = 6, S_VAL = 7, S_P1 = 8, S_P5 = 9, B_TYPE = 10, B_THROW = 11;
    private final RectF[] ctl = new RectF[12];
    private final RectF panelBg = new RectF();
    private int pressed = -1;          // control held down (for hold-to-repeat)
    private float pressTime, repeatTime;
    private boolean dialogOpen;
    private float density = 1f;

    private final SharedPreferences prefs;

    public GameView(Context c) {
        super(c);
        prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        vsCpu = prefs.getBoolean("vsCpu", true);
        gravityIdx = prefs.getInt("gravity", 0);
        winIdx = prefs.getInt("win", 0);
        cpuLevel = prefs.getInt("level", 1);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
        } catch (RuntimeException e) {
            tone = null;
        }
        setKeepScreenOn(true);
        density = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i < ctl.length; i++) ctl[i] = new RectF();
    }

    private void beep(int t, int ms) {
        if (tone == null) return;
        try { tone.startTone(t, ms); } catch (RuntimeException ignored) { }
    }

    public void release() {
        if (tone != null) { tone.release(); tone = null; }
    }

    /** Back button: in a game return to the menu; on the menu let the app close. */
    public boolean onBack() {
        if (state == State.MENU) return false;
        state = State.MENU;
        invalidate();
        return true;
    }

    // ---------------- setup ----------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w; H = h;
        unit = W / 640f;
        sky = new Paint();
        sky.setShader(new LinearGradient(0, 0, 0, H, Color.rgb(8, 10, 48), Color.rgb(36, 40, 110), Shader.TileMode.CLAMP));
        gh = Math.max(H * 0.085f, 36f);
        if (state != State.MENU) newRound();
    }

    private float gravity() { return GRAVITY_VALUES[gravityIdx]; }

    private void newGame() {
        score[0] = score[1] = 0;
        turn = 0;
        hasLast0 = hasLast1 = false;
        newRound();
    }

    private void newRound() {
        if (W == 0) return;
        buildCity();
        wind = rnd.nextInt(11) - 5;
        if (rnd.nextInt(3) == 0) wind += (rnd.nextBoolean() ? 1 : -1) * (rnd.nextInt(6));
        alive[0] = alive[1] = true;
        blasts.clear();
        bananaVisible = false;
        sunShocked = false;
        loser = winner = -1;
        cpuError = cpuLevel == 0 ? 0.30f : cpuLevel == 1 ? 0.18f : 0.09f;
        hasLast0 = hasLast1 = false;
        startTurn();
    }

    private void startTurn() {
        state = State.AIM;
        aiming = false;
        aimAngle = turn == 0 ? lastAngle[0] : lastAngle[1];
        aimSpeed = turn == 0 ? lastSpeed[0] : lastSpeed[1];
        cpuDelay = 0.9f;
        pressed = -1;
        message = "";
        invalidate();
    }

    private void buildCity() {
        if (city != null) city.recycle();
        city = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        cityCanvas = new Canvas(city);
        buildings.clear();
        float gw = gh * 0.75f;
        float minW = Math.max(gw * 1.5f, W * 0.055f), maxW = Math.max(minW * 1.3f, W * 0.1f);
        float x = 0;
        int[] palette = {Color.rgb(0, 150, 160), Color.rgb(170, 30, 40), Color.rgb(150, 150, 160)};
        float slope = rnd.nextInt(4); // 0 flat, 1 up, 2 down, 3 valley
        int i = 0;
        while (x < W) {
            float bw = minW + rnd.nextFloat() * (maxW - minW);
            if (W - (x + bw) < minW) bw = W - x;
            float frac = x / W, base;
            if (slope == 1) base = 0.18f + 0.3f * frac;
            else if (slope == 2) base = 0.48f - 0.3f * frac;
            else if (slope == 3) base = 0.18f + 0.3f * Math.abs(frac - 0.5f) * 2f;
            else base = 0.3f;
            float bhFrac = base + rnd.nextFloat() * 0.22f;
            float top = H - H * Math.min(bhFrac, 0.62f);
            RectF r = new RectF(x, top, x + bw, H);
            buildings.add(r);
            fill.setColor(palette[rnd.nextInt(palette.length)]);
            cityCanvas.drawRect(r.left, r.top, r.right - Math.max(2, W * 0.002f), r.bottom, fill);
            // windows
            float ww = Math.max(3, bw * 0.08f), wh = ww * 1.6f, gapX = ww * 1.6f, gapY = wh * 1.5f;
            for (float wy = top + gapY * 0.6f; wy + wh < H - wh; wy += gapY) {
                for (float wx = r.left + gapX * 0.7f; wx + ww < r.right - gapX * 0.5f; wx += gapX) {
                    fill.setColor(rnd.nextInt(4) == 0 ? Color.rgb(70, 70, 80) : Color.rgb(250, 230, 120));
                    cityCanvas.drawRect(wx, wy, wx + ww, wy + wh, fill);
                }
            }
            x += bw;
            i++;
        }
        int n = buildings.size();
        int left = 1 + rnd.nextInt(Math.max(1, Math.min(2, n / 4)));
        int right = n - 2 - rnd.nextInt(Math.max(1, Math.min(2, n / 4)));
        if (right <= left) { left = 0; right = n - 1; }
        RectF a = buildings.get(left), b = buildings.get(right);
        gx[0] = a.centerX(); gFoot[0] = a.top;
        gx[1] = b.centerX(); gFoot[1] = b.top;
    }

    // ---------------- physics ----------------

    private float windAcc() { return wind * 0.15f; }

    private float handX(int p) { return gx[p] + (p == 0 ? -1 : 1) * gh * 0.25f; }
    private float handY(int p) { return gFoot[p] - gh * 1.08f; }

    private void throwBanana(int p, float angleDeg, float speed) {
        thrower = p;
        double rad = Math.toRadians(p == 0 ? angleDeg : 180 - angleDeg);
        bvx = (float) (speed * Math.cos(rad));
        bvy = (float) (speed * Math.sin(rad));
        bx0 = handX(p); by0 = handY(p);
        bx = bx0; by = by0; bt = 0; spin = 0;
        bananaVisible = true;
        if (p == 0) { lastAngle[0] = angleDeg; lastSpeed[0] = speed; hasLast0 = true; }
        else { lastAngle[1] = angleDeg; lastSpeed[1] = speed; hasLast1 = true; }
        state = State.FLY;
        beep(ToneGenerator.TONE_PROP_BEEP, 60);
    }

    /** Position after t time units, in screen pixels. */
    private float posX(float x0, float vx, float t) { return x0 + (vx * t + 0.5f * windAcc() * t * t) * unit; }
    private float posY(float y0, float vy, float t) { return y0 - (vy * t - 0.5f * gravity() * t * t) * unit; }

    private float timeRate() {
        float g = gravity();
        return 7f * (float) Math.max(1.0, Math.sqrt(9.8 / g));
    }

    private boolean solid(float x, float y) {
        int ix = (int) x, iy = (int) y;
        if (ix < 0 || iy < 0 || ix >= W || iy >= H) return false;
        return (city.getPixel(ix, iy) >>> 24) != 0;
    }

    private RectF gorillaRect(int p) {
        float hw = gh * 0.42f;
        return new RectF(gx[p] - hw, gFoot[p] - gh * 1.05f, gx[p] + hw, gFoot[p]);
    }

    private void stepFlight(float dt) {
        float total = dt * timeRate();
        float step = 0.05f;
        while (total > 0) {
            float d = Math.min(step, total);
            total -= d;
            bt += d;
            bx = posX(bx0, bvx, bt);
            by = posY(by0, bvy, bt);
            spin += d * 4f;
            // the sun is shocked when a banana goes near it
            float sx = W / 2f, sy = H * 0.12f, sr = H * 0.055f;
            if (Math.hypot(bx - sx, by - sy) < sr * 1.6f) sunShocked = true;

            for (int p = 0; p < 2; p++) {
                if (!alive[p]) continue;
                if (p == thrower && bt < 1.2f) continue;
                if (gorillaRect(p).contains(bx, by)) { gorillaHit(p); return; }
            }
            if (solid(bx, by)) { buildingHit(); return; }
            if (bx < -W * 0.1f || bx > W * 1.1f || by > H + 10) { miss(); return; }
        }
    }

    private void buildingHit() {
        float r = gh * 0.32f;
        cityCanvas.drawCircle(bx, by, r, clear);
        blasts.add(new float[]{bx, by, r * 1.1f, 0, 0.35f});
        bananaVisible = false;
        beep(ToneGenerator.TONE_CDMA_PIP, 120);
        state = State.EXPLODE;
        stateTime = 0;
        loser = -1;
    }

    private void miss() {
        bananaVisible = false;
        state = State.EXPLODE;
        stateTime = 0.25f;
        loser = -1;
    }

    private void gorillaHit(int p) {
        bananaVisible = false;
        alive[p] = false;
        float cx = gx[p], cy = gFoot[p] - gh * 0.5f, r = gh * 1.15f;
        cityCanvas.drawCircle(cx, cy, r, clear);
        blasts.add(new float[]{cx, cy, r * 1.25f, 0, 0.9f});
        blasts.add(new float[]{cx, cy - gh * 0.3f, r * 0.8f, -0.15f, 0.8f});
        beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400);
        loser = p;
        winner = 1 - p;
        state = State.EXPLODE;
        stateTime = 0;
    }

    // ---------------- CPU ----------------

    /** Simulates a throw ignoring gorillas; returns the x where it lands at the target's height, or NaN if blocked. */
    private float simulate(int p, float angleDeg, float speed, int target) {
        double rad = Math.toRadians(p == 0 ? angleDeg : 180 - angleDeg);
        float vx = (float) (speed * Math.cos(rad)), vy = (float) (speed * Math.sin(rad));
        float x0 = handX(p), y0 = handY(p);
        float ty = gFoot[target] - gh * 0.5f;
        float prevY = y0;
        for (float t = 0.05f; t < 60; t += 0.05f) {
            float x = posX(x0, vx, t), y = posY(y0, vy, t);
            if (y > prevY && y >= ty) return x;          // falling through the target height
            if (t > 0.6f && solid(x, y)) return x + (p == 0 ? -1 : 1) * 10000; // blocked: short
            if (x < -W || x > 2 * W || y > H) return x;
            prevY = y;
        }
        return Float.NaN;
    }

    private void cpuThrow() {
        int p = turn, target = 1 - p;
        float tx = gx[target];
        float bestA = 45, bestV = 60, bestErr = Float.MAX_VALUE;
        for (int a = 30; a <= 75; a += 5) {
            float lo = 5, hi = 100;
            for (int k = 0; k < 22; k++) {
                float mid = (lo + hi) / 2;
                float lx = simulate(p, a, mid, target);
                if (Float.isNaN(lx)) { hi = mid; continue; }
                float dist = (lx - gx[p]) * (p == 0 ? 1 : -1);
                float want = (tx - gx[p]) * (p == 0 ? 1 : -1);
                if (dist < want) lo = mid; else hi = mid;
            }
            float v = (lo + hi) / 2;
            float lx = simulate(p, a, v, target);
            float err = Float.isNaN(lx) ? Float.MAX_VALUE : Math.abs(lx - tx);
            if (err < bestErr) { bestErr = err; bestA = a; bestV = v; }
        }
        float noise = (float) (rnd.nextGaussian() * cpuError);
        float speed = Math.max(5, Math.min(100, bestV * (1 + noise)));
        float angle = bestA + (float) (rnd.nextGaussian() * cpuError * 12);
        cpuError *= cpuLevel == 2 ? 0.45f : 0.6f;
        aimAngle = angle; aimSpeed = speed;
        throwBanana(p, angle, speed);
    }

    private boolean cpuTurn() { return vsCpu && turn == 1; }

    // ---------------- frame loop ----------------

    private void update(float dt) {
        stateTime += dt;
        for (int i = blasts.size() - 1; i >= 0; i--) {
            float[] b = blasts.get(i);
            b[3] += dt;
            if (b[3] > b[4]) blasts.remove(i);
        }
        switch (state) {
            case AIM:
                if (cpuTurn()) {
                    cpuDelay -= dt;
                    if (cpuDelay <= 0) cpuThrow();
                } else if (pressed >= 0 && isStep(pressed)) {
                    pressTime += dt;
                    if (pressTime > 0.45f) {
                        repeatTime += dt;
                        while (repeatTime > 0.07f) { repeatTime -= 0.07f; applyStep(pressed); }
                    }
                }
                break;
            case FLY:
                stepFlight(dt);
                break;
            case EXPLODE:
                if (stateTime > 0.45f && blasts.isEmpty()) {
                    if (loser >= 0) {
                        score[winner]++;
                        state = State.DANCE;
                        stateTime = 0;
                        beep(ToneGenerator.TONE_PROP_ACK, 300);
                    } else {
                        turn = 1 - turn;
                        startTurn();
                    }
                }
                break;
            case DANCE:
                if (stateTime > 2.2f) {
                    if (score[winner] >= WIN_TARGETS[winIdx]) {
                        state = State.OVER;
                        stateTime = 0;
                    } else {
                        turn = loser; // the player who was hit starts the next round
                        newRound();
                    }
                }
                break;
            default:
                break;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (state != State.MENU && state != State.OVER) update(dt);

        if (sky != null) c.drawRect(0, 0, W, H, sky);
        drawStars(c);
        if (state == State.MENU) {
            drawMenu(c);
            lastFrame = 0;
            return;
        }
        drawSun(c);
        if (city != null) c.drawBitmap(city, 0, 0, null);
        for (int p = 0; p < 2; p++) if (alive[p]) drawGorilla(c, p);
        if (bananaVisible) drawBanana(c, bx, by, spin);
        if (bananaVisible && by < 0) drawOffTop(c);
        drawBlasts(c);
        drawHud(c);
        if (state == State.AIM) drawAim(c);
        if (state == State.OVER) drawGameOver(c);
        postInvalidateOnAnimation();
    }

    // ---------------- drawing ----------------

    private long starSeed = 12345;

    private void drawStars(Canvas c) {
        Random r = new Random(starSeed);
        fill.setColor(Color.argb(170, 255, 255, 255));
        for (int i = 0; i < 70; i++) {
            float x = r.nextFloat() * W, y = r.nextFloat() * H * 0.45f;
            c.drawCircle(x, y, 1 + r.nextFloat() * Math.max(1, W / 900f), fill);
        }
    }

    private void drawSun(Canvas c) {
        float sx = W / 2f, sy = H * 0.12f, r = H * 0.055f;
        stroke.setColor(Color.rgb(255, 215, 40));
        stroke.setStrokeWidth(Math.max(2, r * 0.12f));
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            float r1 = r * 1.15f, r2 = r * (i % 2 == 0 ? 1.6f : 1.4f);
            c.drawLine(sx + (float) Math.cos(a) * r1, sy + (float) Math.sin(a) * r1,
                    sx + (float) Math.cos(a) * r2, sy + (float) Math.sin(a) * r2, stroke);
        }
        fill.setColor(Color.rgb(255, 215, 40));
        c.drawCircle(sx, sy, r, fill);
        fill.setColor(Color.rgb(90, 60, 0));
        c.drawCircle(sx - r * 0.35f, sy - r * 0.2f, r * 0.11f, fill);
        c.drawCircle(sx + r * 0.35f, sy - r * 0.2f, r * 0.11f, fill);
        stroke.setColor(Color.rgb(90, 60, 0));
        stroke.setStrokeWidth(Math.max(2, r * 0.08f));
        if (sunShocked) {
            c.drawCircle(sx, sy + r * 0.35f, r * 0.2f, stroke);
        } else {
            RectF m = new RectF(sx - r * 0.45f, sy - r * 0.05f, sx + r * 0.45f, sy + r * 0.55f);
            c.drawArc(m, 20, 140, false, stroke);
        }
    }

    /** arms: 0 both down, 1 left up, 2 right up, 3 both up */
    private void drawGorilla(Canvas c, int p) {
        int arms = 0;
        if (state == State.FLY && thrower == p && bt < 1.0f) arms = p == 0 ? 1 : 2;
        if (state == State.DANCE && p == winner) arms = ((int) (stateTime * 4)) % 2 == 0 ? 1 : 2;
        if (state == State.OVER && p == winner) arms = 3;
        drawGorillaAt(c, gx[p], gFoot[p], gh, arms);
    }

    private void drawGorillaAt(Canvas c, float cx, float foot, float h, int arms) {
        float u = h / 10f;
        int fur = Color.rgb(120, 72, 30), dark = Color.rgb(70, 40, 15), face = Color.rgb(214, 170, 120);
        fill.setColor(fur);
        // legs
        c.drawRoundRect(new RectF(cx - 2.6f * u, foot - 3.2f * u, cx - 0.6f * u, foot), u, u, fill);
        c.drawRoundRect(new RectF(cx + 0.6f * u, foot - 3.2f * u, cx + 2.6f * u, foot), u, u, fill);
        // body
        c.drawOval(new RectF(cx - 3.2f * u, foot - 7.6f * u, cx + 3.2f * u, foot - 2.2f * u), fill);
        fill.setColor(face);
        c.drawOval(new RectF(cx - 1.7f * u, foot - 6.4f * u, cx + 1.7f * u, foot - 3.0f * u), fill);
        // arms
        stroke.setColor(fur);
        stroke.setStrokeWidth(1.6f * u);
        float sy = foot - 6.6f * u;
        boolean leftUp = arms == 1 || arms == 3, rightUp = arms == 2 || arms == 3;
        drawArm(c, cx - 2.7f * u, sy, -1, leftUp, u);
        drawArm(c, cx + 2.7f * u, sy, 1, rightUp, u);
        // head
        fill.setColor(fur);
        c.drawCircle(cx, foot - 8.6f * u, 1.9f * u, fill);
        fill.setColor(face);
        c.drawOval(new RectF(cx - 1.3f * u, foot - 8.9f * u, cx + 1.3f * u, foot - 7.0f * u), fill);
        fill.setColor(dark);
        c.drawRect(cx - 1.5f * u, foot - 9.5f * u, cx + 1.5f * u, foot - 9.0f * u, fill);
        fill.setColor(Color.BLACK);
        c.drawCircle(cx - 0.6f * u, foot - 8.5f * u, 0.28f * u, fill);
        c.drawCircle(cx + 0.6f * u, foot - 8.5f * u, 0.28f * u, fill);
        c.drawCircle(cx - 0.3f * u, foot - 7.7f * u, 0.15f * u, fill);
        c.drawCircle(cx + 0.3f * u, foot - 7.7f * u, 0.15f * u, fill);
    }

    private void drawArm(Canvas c, float sx, float sy, int side, boolean up, float u) {
        path.reset();
        path.moveTo(sx, sy);
        if (up) {
            path.quadTo(sx + side * 2.2f * u, sy - 1.0f * u, sx + side * 1.2f * u, sy - 4.0f * u);
        } else {
            path.quadTo(sx + side * 1.6f * u, sy + 1.5f * u, sx + side * 1.0f * u, sy + 4.0f * u);
        }
        c.drawPath(path, stroke);
    }

    private void drawBanana(Canvas c, float x, float y, float angle) {
        float r = gh * 0.2f;
        c.save();
        c.translate(x, y);
        c.rotate((float) Math.toDegrees(angle));
        stroke.setColor(Color.rgb(255, 225, 50));
        stroke.setStrokeWidth(Math.max(3, r * 0.45f));
        c.drawArc(new RectF(-r, -r, r, r), 200, 140, false, stroke);
        fill.setColor(Color.rgb(90, 60, 10));
        c.drawCircle((float) (r * Math.cos(Math.toRadians(200))), (float) (r * Math.sin(Math.toRadians(200))), stroke.getStrokeWidth() * 0.4f, fill);
        c.restore();
    }

    private void drawOffTop(Canvas c) {
        float x = Math.max(10, Math.min(W - 10, bx));
        float s = gh * 0.18f;
        fill.setColor(Color.rgb(255, 225, 50));
        path.reset();
        path.moveTo(x, 4);
        path.lineTo(x - s, 4 + s * 1.4f);
        path.lineTo(x + s, 4 + s * 1.4f);
        path.close();
        c.drawPath(path, fill);
    }

    private void drawBlasts(Canvas c) {
        for (float[] b : blasts) {
            if (b[3] < 0) continue;
            float k = b[3] / b[4];
            float r = b[2] * (0.3f + 0.7f * k);
            int alpha = (int) (255 * (1 - k));
            fill.setColor(Color.argb(alpha, 255, 80, 20));
            c.drawCircle(b[0], b[1], r, fill);
            fill.setColor(Color.argb(alpha, 255, 210, 60));
            c.drawCircle(b[0], b[1], r * 0.6f, fill);
        }
    }

    private void textAt(Canvas c, String s, float x, float y, float size, int color, Paint.Align align) {
        text.setTextSize(size);
        text.setTextAlign(align);
        text.setColor(Color.argb(200, 0, 0, 0));
        c.drawText(s, x + size * 0.07f, y + size * 0.07f, text);
        text.setColor(color);
        c.drawText(s, x, y, text);
    }

    private String name(int p) { return p == 0 ? "Player 1" : (vsCpu ? "CPU" : "Player 2"); }

    private void drawHud(Canvas c) {
        float ts = Math.max(14, H * 0.04f);
        textAt(c, name(0), ts * 0.6f, ts * 1.3f, ts, turn == 0 ? Color.YELLOW : Color.WHITE, Paint.Align.LEFT);
        textAt(c, name(1), W - ts * 0.6f, ts * 1.3f, ts, turn == 1 ? Color.YELLOW : Color.WHITE, Paint.Align.RIGHT);
        // score box at the bottom
        String sc = score[0] + " > Score < " + score[1];
        text.setTextSize(ts);
        float tw = text.measureText(sc);
        fill.setColor(Color.rgb(20, 20, 160));
        c.drawRect(W / 2f - tw / 2 - ts * 0.4f, H - ts * 2.6f, W / 2f + tw / 2 + ts * 0.4f, H - ts * 1.25f, fill);
        textAt(c, sc, W / 2f, H - ts * 1.6f, ts, Color.WHITE, Paint.Align.CENTER);
        // wind arrow
        if (wind != 0) {
            float len = wind * W * 0.012f, y = H - ts * 0.6f, x0 = W / 2f;
            stroke.setColor(Color.rgb(255, 120, 60));
            stroke.setStrokeWidth(Math.max(3, ts * 0.15f));
            c.drawLine(x0, y, x0 + len, y, stroke);
            float hd = Math.signum(len) * ts * 0.5f;
            c.drawLine(x0 + len, y, x0 + len - hd, y - ts * 0.3f, stroke);
            c.drawLine(x0 + len, y, x0 + len - hd, y + ts * 0.3f, stroke);
        }
        if (state == State.DANCE) {
            textAt(c, name(winner) + " scores!", W / 2f, H * 0.32f, ts * 1.3f, Color.YELLOW, Paint.Align.CENTER);
        }
    }

    private static String fmt(float v) {
        return Math.abs(v - Math.round(v)) < 0.05f ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v);
    }

    private float panelText() { return Math.max(13, Math.min(H * 0.04f, W * 0.021f)); }

    /** Lays out the aiming panel in the top corner on the thrower's side. */
    private void layoutPanel(int p) {
        float ts = Math.max(14, H * 0.04f), pt = panelText();
        float bs = Math.max(pt * 1.75f, 40 * density), g = bs * 0.12f;
        float lw = pt * 3.3f, vw = bs * 1.5f, tw = bs * 1.9f;
        float rowW = lw + 4 * bs + vw + 5 * g;
        float total = rowW + g * 2 + tw;
        float m = ts * 0.6f;
        float x0 = p == 0 ? m : W - m - total;
        float y0 = ts * 1.75f;
        // rows: the label sits at x0, buttons follow
        for (int row = 0; row < 2; row++) {
            float y = y0 + row * (bs + g), x = x0 + lw;
            int base = row == 0 ? A_M5 : S_M5;
            for (int k = 0; k < 5; k++) {
                float w = k == 2 ? vw : bs;
                ctl[base + k].set(x, y, x + w, y + bs);
                x += w + g;
            }
        }
        float cx = x0 + rowW + g;
        // for the right-hand player put Type/THROW toward the middle of the screen
        if (p == 1) {
            float shift = tw + g * 2;
            for (int i = 0; i < 10; i++) ctl[i].offset(shift, 0);
            cx = x0;
        }
        ctl[B_TYPE].set(cx, y0, cx + tw, y0 + bs * 0.8f);
        ctl[B_THROW].set(cx, y0 + bs * 0.8f + g, cx + tw, y0 + 2 * bs + g);
        panelBg.set(x0 - g, y0 - g, x0 + total + g, y0 + 2 * bs + 2 * g);
    }

    private void drawAim(Canvas c) {
        float ts = Math.max(14, H * 0.04f);
        int p = turn;
        float infoX = p == 0 ? ts * 0.6f : W - ts * 0.6f;
        Paint.Align al = p == 0 ? Paint.Align.LEFT : Paint.Align.RIGHT;
        if (cpuTurn()) {
            textAt(c, "Thinking...", infoX, ts * 2.6f, ts * 0.8f, Color.WHITE, al);
            return;
        }
        // arrow from the gorilla's hand; length shows speed
        double rad = Math.toRadians(p == 0 ? aimAngle : 180 - aimAngle);
        float len = gh * (0.6f + 2.4f * aimSpeed / 100f);
        float hx = handX(p), hy = handY(p);
        float ex = hx + (float) Math.cos(rad) * len, ey = hy - (float) Math.sin(rad) * len;
        stroke.setColor(Color.argb(220, 255, 255, 255));
        stroke.setStrokeWidth(Math.max(3, ts * 0.12f));
        c.drawLine(hx, hy, ex, ey, stroke);
        float hs = ts * 0.5f;
        double back1 = rad + Math.PI - 0.45, back2 = rad + Math.PI + 0.45;
        c.drawLine(ex, ey, ex + (float) Math.cos(back1) * hs, ey - (float) Math.sin(back1) * hs, stroke);
        c.drawLine(ex, ey, ex + (float) Math.cos(back2) * hs, ey - (float) Math.sin(back2) * hs, stroke);

        layoutPanel(p);
        float pt = panelText();
        fill.setColor(Color.argb(150, 0, 0, 30));
        c.drawRoundRect(panelBg, pt * 0.5f, pt * 0.5f, fill);
        float labelX = (p == 0 ? panelBg.left : ctl[A_M5].left - (pt * 3.3f)) + pt * 0.35f;
        textAt(c, "Angle", labelX, ctl[A_M5].centerY() + pt * 0.35f, pt, Color.WHITE, Paint.Align.LEFT);
        textAt(c, "Speed", labelX, ctl[S_M5].centerY() + pt * 0.35f, pt, Color.WHITE, Paint.Align.LEFT);
        String[] lab = {"-5", "-1", "", "+1", "+5"};
        for (int k = 0; k < 5; k++) {
            if (k == 2) continue;
            smallButton(c, ctl[A_M5 + k], lab[k], pt, pressed == A_M5 + k);
            smallButton(c, ctl[S_M5 + k], lab[k], pt, pressed == S_M5 + k);
        }
        valueBox(c, ctl[A_VAL], fmt(aimAngle) + "\u00b0", pt);
        valueBox(c, ctl[S_VAL], fmt(aimSpeed), pt);
        button(c, ctl[B_TYPE], "Type", pt * 0.9f, false);
        button(c, ctl[B_THROW], "THROW", pt * 1.1f, true);

        boolean has = p == 0 ? hasLast0 : hasLast1;
        float below = panelBg.bottom + pt * 1.2f;
        if (has) {
            float la = p == 0 ? lastAngle[0] : lastAngle[1], lv = p == 0 ? lastSpeed[0] : lastSpeed[1];
            textAt(c, "Last: " + fmt(la) + "\u00b0  " + fmt(lv), infoX, below, pt * 0.85f, Color.LTGRAY, al);
        } else {
            textAt(c, "Tap +/- or the numbers, or drag to aim", infoX, below, pt * 0.85f, Color.LTGRAY, al);
        }
    }

    private void smallButton(Canvas c, RectF r, String label, float ts, boolean down) {
        fill.setColor(down ? Color.rgb(90, 90, 170) : Color.argb(220, 40, 40, 110));
        c.drawRoundRect(r, ts * 0.35f, ts * 0.35f, fill);
        stroke.setColor(Color.argb(140, 255, 255, 255));
        stroke.setStrokeWidth(2);
        c.drawRoundRect(r, ts * 0.35f, ts * 0.35f, stroke);
        text.setTextSize(ts);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.WHITE);
        c.drawText(label, r.centerX(), r.centerY() + ts * 0.35f, text);
    }

    private void valueBox(Canvas c, RectF r, String label, float ts) {
        fill.setColor(Color.rgb(250, 245, 220));
        c.drawRoundRect(r, ts * 0.35f, ts * 0.35f, fill);
        text.setTextSize(ts * 1.05f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.rgb(30, 20, 0));
        c.drawText(label, r.centerX(), r.centerY() + ts * 0.38f, text);
    }

    private void button(Canvas c, RectF r, String label, float ts, boolean primary) {
        fill.setColor(primary ? Color.rgb(230, 180, 20) : Color.argb(200, 30, 30, 90));
        c.drawRoundRect(r, ts * 0.4f, ts * 0.4f, fill);
        stroke.setColor(Color.argb(160, 255, 255, 255));
        stroke.setStrokeWidth(2);
        c.drawRoundRect(r, ts * 0.4f, ts * 0.4f, stroke);
        text.setTextSize(ts);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(primary ? Color.rgb(40, 20, 0) : Color.WHITE);
        c.drawText(label, r.centerX(), r.centerY() + ts * 0.35f, text);
    }

    private void drawMenu(Canvas c) {
        float ts = Math.max(16, Math.min(H * 0.05f, W * 0.03f));
        // decorative skyline silhouette
        fill.setColor(Color.rgb(20, 22, 60));
        Random r = new Random(7);
        for (float x = 0; x < W; ) {
            float bw = W * (0.05f + r.nextFloat() * 0.05f);
            c.drawRect(x, H * (0.62f + r.nextFloat() * 0.2f), x + bw - 3, H, fill);
            x += bw;
        }
        drawGorillaAt(c, W * 0.14f, H * 0.95f, H * 0.28f, 1);
        drawGorillaAt(c, W * 0.86f, H * 0.95f, H * 0.28f, 2);
        drawBanana(c, W * 0.5f, H * 0.12f, 0.6f);
        textAt(c, "BANANA TOSS", W / 2f, H * 0.2f, ts * 2.2f, Color.rgb(255, 215, 40), Paint.Align.CENTER);

        float bw = Math.min(W * 0.42f, ts * 18), bh = ts * 2.1f, x0 = W / 2f - bw / 2, y = H * 0.28f, gap = bh * 0.25f;
        bMode.set(x0, y, x0 + bw, y + bh); y += bh + gap;
        bLevel.set(x0, y, x0 + bw, y + bh); if (vsCpu) y += bh + gap;
        bGravity.set(x0, y, x0 + bw, y + bh); y += bh + gap;
        bWin.set(x0, y, x0 + bw, y + bh); y += bh + gap * 2;
        bStart.set(x0, y, x0 + bw, y + bh);
        button(c, bMode, vsCpu ? "Mode: 1 player vs CPU" : "Mode: 2 players", ts, false);
        if (vsCpu) button(c, bLevel, "CPU: " + new String[]{"Easy", "Normal", "Hard"}[cpuLevel], ts, false);
        else bLevel.setEmpty();
        button(c, bGravity, String.format(Locale.US, "Gravity: %s (%.1f)", GRAVITY_NAMES[gravityIdx], GRAVITY_VALUES[gravityIdx]), ts, false);
        button(c, bWin, "First to " + WIN_TARGETS[winIdx] + " wins", ts, false);
        button(c, bStart, "START", ts * 1.2f, true);
    }

    private void drawGameOver(Canvas c) {
        float ts = Math.max(16, H * 0.05f);
        fill.setColor(Color.argb(150, 0, 0, 0));
        c.drawRect(0, 0, W, H, fill);
        textAt(c, "GAME OVER", W / 2f, H * 0.28f, ts * 1.8f, Color.rgb(255, 215, 40), Paint.Align.CENTER);
        textAt(c, name(winner) + " wins  " + score[0] + " - " + score[1], W / 2f, H * 0.4f, ts, Color.WHITE, Paint.Align.CENTER);
        float bw = Math.min(W * 0.3f, ts * 11), bh = ts * 2f, y = H * 0.5f;
        bAgain.set(W / 2f - bw - ts * 0.4f, y, W / 2f - ts * 0.4f, y + bh);
        bMenu.set(W / 2f + ts * 0.4f, y, W / 2f + bw + ts * 0.4f, y + bh);
        button(c, bAgain, "Play again", ts, true);
        button(c, bMenu, "Menu", ts, false);
    }

    // ---------------- input ----------------

    private void saveSettings() {
        prefs.edit().putBoolean("vsCpu", vsCpu).putInt("gravity", gravityIdx).putInt("win", winIdx).putInt("level", cpuLevel).apply();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (state == State.MENU) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                if (bMode.contains(x, y)) vsCpu = !vsCpu;
                else if (vsCpu && bLevel.contains(x, y)) cpuLevel = (cpuLevel + 1) % 3;
                else if (bGravity.contains(x, y)) gravityIdx = (gravityIdx + 1) % GRAVITY_NAMES.length;
                else if (bWin.contains(x, y)) winIdx = (winIdx + 1) % WIN_TARGETS.length;
                else if (bStart.contains(x, y)) {
                    saveSettings();
                    starSeed = rnd.nextLong();
                    lastAngle[0] = lastAngle[1] = 45;
                    lastSpeed[0] = lastSpeed[1] = 50;
                    newGame();
                    lastFrame = 0;
                }
                saveSettings();
                invalidate();
            }
            return true;
        }
        if (state == State.OVER) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                if (bAgain.contains(x, y)) { newGame(); lastFrame = 0; }
                else if (bMenu.contains(x, y)) { state = State.MENU; }
                invalidate();
            }
            return true;
        }
        if (state != State.AIM || cpuTurn() || dialogOpen) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                layoutPanel(turn);
                int hit = -1;
                for (int i = 0; i < ctl.length; i++) {
                    RectF r = ctl[i];
                    float pad = r.height() * 0.06f;
                    if (x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad) { hit = i; break; }
                }
                if (hit >= 0) {
                    pressed = hit; pressTime = 0; repeatTime = 0;
                    if (isStep(hit)) applyStep(hit);
                } else if (panelBg.contains(x, y)) {
                    pressed = -2; // ignore touches on the panel background
                } else {
                    pressed = -1;
                    downX = x; downY = y;
                    aiming = true;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (aiming) updateAim(x, y);
                break;
            case MotionEvent.ACTION_UP: {
                int hit = pressed;
                pressed = -1;
                aiming = false;
                if (hit == B_THROW && ctl[B_THROW].contains(x, y)) throwBanana(turn, aimAngle, aimSpeed);
                else if ((hit == B_TYPE || hit == A_VAL || hit == S_VAL) && ctl[hit].contains(x, y)) showInputDialog(hit == S_VAL);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                aiming = false;
                pressed = -1;
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private static boolean isStep(int i) { return i != A_VAL && i != S_VAL && i != B_TYPE && i != B_THROW && i >= 0; }

    private void applyStep(int i) {
        float[] d = {-5, -1, 0, 1, 5};
        if (i <= A_P5) aimAngle = clampAngle(Math.round(aimAngle) + d[i - A_M5]);
        else aimSpeed = clampSpeed(Math.round(aimSpeed) + d[i - S_M5]);
        beep(ToneGenerator.TONE_PROP_BEEP2, 15);
        invalidate();
    }

    private static float clampAngle(float a) { return Math.max(0, Math.min(180, a)); }
    private static float clampSpeed(float v) { return Math.max(1, Math.min(100, v)); }

    private static Float parse(EditText e) {
        try { return Float.parseFloat(e.getText().toString().trim().replace(',', '.')); }
        catch (NumberFormatException ex) { return null; }
    }

    /** Lets the player type an exact angle and speed. */
    private void showInputDialog(boolean focusSpeed) {
        final int p = turn;
        Context ctx = getContext();
        float d = density;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText ea = new EditText(ctx), es = new EditText(ctx);
        TextView la = new TextView(ctx), ls = new TextView(ctx);
        la.setText("Angle in degrees (0\u2013180, 90 = straight up)");
        ls.setText("Speed (1\u2013100)");
        ea.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        es.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ea.setText(fmt(aimAngle));
        es.setText(fmt(aimSpeed));
        ea.setSelectAllOnFocus(true);
        es.setSelectAllOnFocus(true);
        ea.setGravity(Gravity.CENTER);
        es.setGravity(Gravity.CENTER);
        box.addView(la); box.addView(ea); box.addView(ls); box.addView(es);
        dialogOpen = true;
        AlertDialog dlg = new AlertDialog.Builder(ctx, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(name(p) + ": set your throw")
                .setView(box)
                .setPositiveButton("Throw", (di, w) -> {
                    if (apply(ea, es) && state == State.AIM && turn == p) throwBanana(p, aimAngle, aimSpeed);
                })
                .setNeutralButton("Set only", (di, w) -> apply(ea, es))
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnDismissListener(di -> { dialogOpen = false; invalidate(); });
        dlg.show();
        (focusSpeed ? es : ea).requestFocus();
        if (dlg.getWindow() != null) dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private boolean apply(EditText ea, EditText es) {
        Float a = parse(ea), v = parse(es);
        if (a != null) aimAngle = clampAngle(a);
        if (v != null) aimSpeed = clampSpeed(v);
        invalidate();
        return a != null && v != null;
    }

    /** Dragging (away from the panel) sets the aim: direction is the angle, length the speed. Use THROW to fire. */
    private void updateAim(float x, float y) {
        float dx = x - downX, dy = downY - y;
        float len = (float) Math.hypot(dx, dy);
        if (len < gh * 0.25f) return;
        float deg = (float) Math.toDegrees(Math.atan2(dy, turn == 0 ? dx : -dx));
        aimAngle = clampAngle(Math.round(deg));
        float maxDrag = H * 0.55f;
        aimSpeed = Math.max(1, Math.min(100, Math.round(len / maxDrag * 100)));
    }
}
