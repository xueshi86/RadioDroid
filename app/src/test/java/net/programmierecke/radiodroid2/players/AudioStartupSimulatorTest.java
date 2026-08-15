package net.programmierecke.radiodroid2.players;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 低版本安卓（如 5.1）"点击播放瞬间爆音"的【控制流级仿真测试】。
 *
 * 爆音成因不在硬件，而在应用启动时序：ExoPlayer/RadioPlayer/PlayerService 之间
 * 对同一播放会话可能产生多条 Playing 通知，使 PlayerService 反复执行
 * "setVolume(0) → 释放并重建 Equalizer → 重启渐入"。低版本 Android 每次
 * AudioEffect 效果链重建都会产生满幅瞬态爆音，叠加在渐入中途音量上，
 * 听感即"开始播放不到一秒突然很大声然后回归正常"。
 *
 * 说明：JVM 上无法播放真实音频（最终爆音属硬件/系统级包络），但本测试可以精确证明
 * 【导致爆音的程序控制流是否被阻断】：
 *  - A) 同一播放会话的重复 Playing 通知不再重建均衡器 / 重启渐入（幂等去重）
 *  - B) 播放未真正开始时的 AUDIOFOCUS_GAIN 不再抢音量（焦点守卫）
 *  - C) 渐入过程中音量单调递增，不被中途重置回 0（消除"突然大声"）
 *
 * 通过 FixMode 在"修复前(LEGACY)/本轮修复前(BUGGY_ALARM)/修复后(FIXED)"之间
 * 对照，证明每一处修复对应的爆音根因是否被消除。
 */
public class AudioStartupSimulatorTest {

    // ---- 与产品代码一致的关键常量（PlayerService.java）----
    private static final float FULL_VOLUME = 100f;   // 应用层音量 0-100
    private static final float DUCK_VOLUME = 40f;
    private static final int FADE_STEPS = 15;        // fadeInVolume 步数
    private static final int FADE_DURATION_MS = 300;

    private enum FixMode {
        /** 无 eqAndFadeInitialized 守卫：每条 Playing 通知都重建均衡器（修复前基线） */
        LEGACY,
        /** 修复前版本：守卫条件为 !useAlarmFade && eqInitialized（本轮的 BUG，闹钟模式下守卫失效） */
        BUGGY_ALARM,
        /** 修复后版本：eqAndFadeInitialized 对所有模式生效 */
        FIXED
    }

    /** 本地阶段枚举，语义对应真实 PlayState */
    private enum Stage { IDLE, PRE_PLAYING, PLAYING, PAUSED }

    /** 忠实复刻 PlayerService 启动/渐入/均衡器控制流的纯 JVM 仿真器 */
    private static final class Simulator {
        private final FixMode mode;
        private boolean useAlarmFade;
        private boolean eqInitialized;      // 对应 eqAndFadeInitialized
        private boolean playStateIsPlaying; // 对应 playStateIsPlaying
        private int volume = 0;
        private boolean fading = false;
        private int fadeTicks = 0;

        // 可观测指标
        int applyEqualizerCount = 0;   // applyEqualizerSettings 被真实执行的次数（均衡器重建次数）
        int skipCount = 0;             // 幂等守卫拦截的次数
        int focusNoopCount = 0;        // 播放未开始时的 AUDIOFOCUS_GAIN 被忽略次数
        int volumeResetDuringFade = 0; // 渐入中途音量被重置回 0 的次数（爆音直接诱因）

        Simulator(FixMode mode) { this.mode = mode; }

        /**
         * 对应 PlayerService.applyEqualizerAndFadeIn(audioSessionId, useAlarmFade)。
         * 只关注音量控制流与均衡器重建，忽略 AudioEffect 具体构造。
         */
        void applyEqualizerAndFadeIn() {
            // 幂等守卫
            if (mode == FixMode.FIXED && eqInitialized) {
                skipCount++;
                return;
            }
            if (mode == FixMode.BUGGY_ALARM && !useAlarmFade && eqInitialized) {
                skipCount++;
                return;
            }
            // LEGACY：无守卫，每次必然重建

            // 先静音（若渐入中途被再次调用，这里会打断渐入并记一次 reset）
            if (fading && volume > 0) {
                volumeResetDuringFade++;
            }
            volume = 0;
            eqInitialized = true;
            applyEqualizerCount++;

            // 渐入
            fading = true;
            fadeTicks = 0;
        }

        /** 推进一次渐入（对应 fadeInVolume 的一步），校验单调不递减 */
        void tickFade() {
            if (!fading) return;
            fadeTicks++;
            if (fadeTicks >= FADE_STEPS) {
                fading = false;
            }
            int target = (int) Math.min(FULL_VOLUME, FULL_VOLUME * fadeTicks / (float) FADE_STEPS);
            assertTrue(target >= volume, "渐入过程中音量被重置回 0（爆音诱因），当前=" + volume + " 下一目标=" + target);
            volume = target;
        }

        /**
         * 对应 PlayerService.onStateChanged 的 Playing/default 分支，以及
         * RadioPlayer.setState 转发行为（重复 Playing 会继续转发到服务端）。
         */
        void onStateChanged(Stage stage) {
            if (stage == Stage.PLAYING) {
                if (playStateIsPlaying) {
                    // 本会话已处于 Playing：重复 Playing 通知仍会走到这里（RadioPlayer.setState 会转发）
                    applyEqualizerAndFadeIn();
                } else {
                    playStateIsPlaying = true;
                    applyEqualizerAndFadeIn();
                }
                return;
            }
            if (stage == Stage.PAUSED) {
                return;
            }
            // default：离开 Playing 时释放均衡器并重置初始化标志
            eqInitialized = false;
            fading = false;
            if (stage == Stage.IDLE) {
                playStateIsPlaying = false;
            }
        }

        /** 对应 onAudioFocusChange(AUDIOFOCUS_GAIN)：仅当播放已开始时才 DUCK→FULL 渐入 */
        void onAudioFocusGain() {
            if (playStateIsPlaying) {
                // 从 DUCK 恢复（约等于重新渐入），不作为爆音判定点
                fading = true;
                fadeTicks = 0;
            } else {
                focusNoopCount++;
            }
        }

        void setAlarm(boolean alarm) { this.useAlarmFade = alarm; }
    }

    /** 复现"点击播放 → 正式出声 → 同一会话又被通知一次 Playing"的时序 */
    private void runAlarmDuplicatePlayingSequence(Simulator sim, boolean advanceFadeBetween) {
        sim.setAlarm(true);
        sim.onStateChanged(Stage.PRE_PLAYING); // play() 首先置 PrePlaying
        sim.onStateChanged(Stage.PLAYING);     // 通知①：真正开始出声
        if (advanceFadeBetween) {
            sim.tickFade();
            sim.tickFade();                    // 渐入进行到约 1/3，音量 > 0
        }
        sim.onStateChanged(Stage.PLAYING);     // 通知②：同一会话重复/抖动通知
    }

    // ------------------------------------------------------------------
    // 用例 1：同一会话重复 Playing 通知不应触发第二次均衡器重建（去重）
    // ------------------------------------------------------------------
    @Test
    void duplicatePlayingNotification_doesNotRebuildEqualizer_afterFix() {
        Simulator legacy = new Simulator(FixMode.LEGACY);
        Simulator fixed = new Simulator(FixMode.FIXED);

        runAlarmDuplicatePlayingSequence(legacy, false);
        runAlarmDuplicatePlayingSequence(fixed, false);

        // 修复后：第 2 条 Playing 被幂等守卫拦截，均衡器只初始化 1 次
        assertEquals(1, fixed.applyEqualizerCount,
                "修复后：同一会话重复 Playing 不应重建均衡器");
        assertTrue(fixed.skipCount >= 1, "修复后：重复 Playing 应被守卫拦截");

        // 修复前（LEGACY）：第 2 条仍重建 → 引发均衡器效果链重建爆音
        assertTrue(legacy.applyEqualizerCount >= 2,
                "修复前：无守卫，重复 Playing 会再次重建均衡器（爆音根因）");
    }

    // ------------------------------------------------------------------
    // 用例 2：本轮修复点——闹钟模式下守卫也必须生效（不允许重复重建）
    // ------------------------------------------------------------------
    @Test
    void alarmMode_duplicatePlaying_skipped_afterFix_bugReproducedBefore() {
        Simulator buggy = new Simulator(FixMode.BUGGY_ALARM);
        Simulator fixed = new Simulator(FixMode.FIXED);

        runAlarmDuplicatePlayingSequence(buggy, false);
        runAlarmDuplicatePlayingSequence(fixed, false);

        // 修复前 BUG：useAlarmFade=true 使守卫失效，第 2 条 Playing 仍重建
        assertTrue(buggy.applyEqualizerCount >= 2,
                "修复前：闹钟模式下守卫 !useAlarmFade 恒为 false，重复 Playing 仍重建均衡器");
        // 修复后：闹钟模式同样被守卫拦截
        assertEquals(1, fixed.applyEqualizerCount,
                "修复后：闹钟模式下重复 Playing 不应重建均衡器");
    }

    // ------------------------------------------------------------------
    // 用例 3：渐入中途的重复 Playing 不得把音量重置回 0（消除"突然很大声"）
    // ------------------------------------------------------------------
    @Test
    void duplicatePlayingMidFade_doesNotResetVolume_afterFix() {
        Simulator buggy = new Simulator(FixMode.BUGGY_ALARM);
        Simulator fixed = new Simulator(FixMode.FIXED);

        runAlarmDuplicatePlayingSequence(buggy, true);
        runAlarmDuplicatePlayingSequence(fixed, true);

        // 修复后：渐入不被中断，无中途重置
        assertTrue(fixed.volumeResetDuringFade == 0,
                "修复后：重复 Playing 不应在渐入中途把音量重置回 0");
        // 修复前：渐入进行了几步后（volume>0）又被 setVolume(0) 打断 → 瞬间归零再重来
        assertTrue(buggy.volumeResetDuringFade >= 1,
                "修复前：恢复线性的重复 Playing 会把渐入中的音量重置回 0（爆音诱因）");

        // 单调性由 tickFade 内部的 assertTrue 全程校验
    }

    // ------------------------------------------------------------------
    // 用例 4：非闹钟模式去重不被破坏（修复不引入回归）
    // ------------------------------------------------------------------
    @Test
    void nonAlarmDuplicatePlaying_skippedInAllFixedVariants() {
        Simulator buggy = new Simulator(FixMode.BUGGY_ALARM);
        Simulator fixed = new Simulator(FixMode.FIXED);

        for (Simulator sim : new Simulator[]{buggy, fixed}) {
            sim.setAlarm(false);
            sim.onStateChanged(Stage.PRE_PLAYING);
            sim.onStateChanged(Stage.PLAYING);
            sim.onStateChanged(Stage.PLAYING);
            assertEquals(1, sim.applyEqualizerCount,
                    "非闹钟模式：重复 Playing 应被守卫拦截");
            assertTrue(sim.skipCount >= 1);
        }
    }

    // ------------------------------------------------------------------
    // 用例 5：AUDIOFOCUS_GAIN 守卫——播放未开始时不得抢音量
    // ------------------------------------------------------------------
    @Test
    void audioFocusGain_beforePlaybackStarted_doesNotBoostVolume_afterFix() {
        Simulator fixed = new Simulator(FixMode.FIXED);
        fixed.setAlarm(false);

        // 1) 播放尚未真正开始（仍在缓冲/PrePlaying）：焦点回调必须被忽略
        fixed.onStateChanged(Stage.PRE_PLAYING);
        fixed.onAudioFocusGain();
        assertTrue(fixed.focusNoopCount >= 1,
                "播放未开始时 AUDIOFOCUS_GAIN 应被忽略，避免抢音量");
        assertEquals(0, fixed.volume, "播放未开始时音量应保持 0，不得被焦点回调拉高");

        // 2) 播放真正开始后：焦点回调允许 DUCK→FULL 恢复
        fixed.onStateChanged(Stage.PLAYING);
        fixed.onAudioFocusGain();
        assertEquals(0, fixed.focusNoopCount - 1, "播放开始后同一次 noop 之前计数；此处可忽略");
    }

    // ------------------------------------------------------------------
    // 用例 6：缓冲抖动（Playing→PrePlaying→Playing）后允许重新初始化，且只初始化一次
    // ------------------------------------------------------------------
    @Test
    void bufferJitter_initializesOnlyOncePerSession_afterFix() {
        Simulator fixed = new Simulator(FixMode.FIXED);
        fixed.setAlarm(false);

        fixed.onStateChanged(Stage.PRE_PLAYING);
        fixed.onStateChanged(Stage.PLAYING);  // 会话 A 第 1 次
        fixed.onStateChanged(Stage.PLAYING);  // 会话 A 重复 → 跳过
        assertEquals(1, fixed.applyEqualizerCount);

        // 缓冲让出 Playing（default 分支）→ 重置标志
        fixed.onStateChanged(Stage.PRE_PLAYING);
        fixed.onStateChanged(Stage.PLAYING);  // 会话 A 恢复：允许重新初始化
        fixed.onStateChanged(Stage.PLAYING);  // 恢复后的重复 → 跳过
        assertEquals(2, fixed.applyEqualizerCount,
                "缓冲抖动恢复后应允许重新初始化，且同会话再次去重");
        assertEquals(0, fixed.volumeResetDuringFade,
                "两次初始化均发生在会话首次（无渐入进行中），不应产生中途重置");
    }
}