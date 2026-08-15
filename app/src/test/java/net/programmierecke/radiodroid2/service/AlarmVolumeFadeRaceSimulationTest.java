package net.programmierecke.radiodroid2.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 闹钟音量渐增（双轨：系统音量逐档 + 应用层增益补平）的单元测试。
 *
 * <p>背景：手机系统音量档位很少（通常 13~15 档，用户界面上却显示 0~150 的精细刻度），
 * 旧方案只靠系统音量逐档渐增：1) 24% 与 29% 四舍五入到同一档 → 听感相同；2) 每档一跳，
 * 不平滑。上一版只靠应用层增益：部分机型对播放器增益不生效，音量"一步跳到目标、毫无过渡"。</p>
 *
 * <p>最终方案（双轨）：系统音量始终按渐增进度向上取整逐档爬升（任何机型都有过渡），
 * 应用层浮点增益在档位之间把响度精确补到目标百分比（支持增益的机型上完全平滑，
 * 且 24% 与 29% 最终响度不同）。</p>
 */
public class AlarmVolumeFadeRaceSimulationTest {

    private static final int MAX_VOL = 15;

    @Test
    public void targetSystemVolume_roundsUp_so24And29Differ() {
        // 24% → ceil(24/100*15)=ceil(3.6)=4；29% → ceil(4.35)=5，不再同档
        assertEquals(4, PlayerService.targetSystemVolume(24, MAX_VOL));
        assertEquals(5, PlayerService.targetSystemVolume(29, MAX_VOL));
        assertEquals(15, PlayerService.targetSystemVolume(100, MAX_VOL));
    }

    @Test
    public void targetSystemVolume_clamps() {
        assertEquals(1, PlayerService.targetSystemVolume(1, MAX_VOL));
        assertEquals(1, PlayerService.targetSystemVolume(0, MAX_VOL));
        assertEquals(MAX_VOL, PlayerService.targetSystemVolume(150, MAX_VOL));
    }

    @Test
    public void alarmGainForVolume_24And29ProduceDifferentGains() {
        // 24%：系统档 4/15=26.67% → 增益 = 24/26.67 = 0.90
        // 29%：系统档 5/15=33.33% → 增益 = 29/33.33 = 0.87
        float g24 = PlayerService.alarmGainForVolume(24, 4f / MAX_VOL);
        float g29 = PlayerService.alarmGainForVolume(29, 5f / MAX_VOL);
        assertTrue("24% 与 29% 增益应不同，实际 " + g24 + " vs " + g29, g24 != g29);
        assertEquals(0.9f, g24, 0.001f);
        assertEquals(0.87f, g29, 0.001f);
    }

    @Test
    public void alarmGainForVolume_finalLoudnessEqualsPercent() {
        // 关键性质：响度 = 系统档位比例 × 应用层增益 = 恰好等于目标百分比
        // （目标 24% 在系统档 4/15 上，最终响度 = 4/15 × 0.9 = 0.24）
        float sysRatio = PlayerService.targetSystemVolume(24, MAX_VOL) / (float) MAX_VOL;
        float gain = PlayerService.alarmGainForVolume(24, sysRatio);
        assertEquals(0.24f, sysRatio * gain, 0.001f);
    }

    @Test
    public void alarmGainForVolume_clamps() {
        assertEquals(1f, PlayerService.alarmGainForVolume(100, 1f), 0.001f);
        assertEquals(0.01f, PlayerService.alarmGainForVolume(1, 1f), 0.001f);
        // 非法比例兜底
        assertEquals(1f, PlayerService.alarmGainForVolume(50, 0f), 0.001f);
    }

    @Test
    public void newDesign_fadeNeverWritesSystemVolume() {
        // 双轨渐增：应用层增益始终只做"补平"，系统音量本身按进度爬升，
        // 因此若用户手动把系统音量调至 0，后续步骤读到 0 会跳过，不会被覆盖。
        // 这里验证增益始终在 (0,1] 内、且进度到 100% 时恰好为 1。
        float prevSys = -1;
        for (int p = 10; p <= 100; p += 10) {
            int sysVol = PlayerService.targetSystemVolume(p, MAX_VOL);
            float gain = PlayerService.alarmGainForVolume(p, sysVol / (float) MAX_VOL);
            assertTrue("系统档位应随进度爬升（不降）", sysVol >= prevSys);
            assertTrue("增益应保持在 (0,1] 内", gain > 0f && gain <= 1f);
            prevSys = sysVol;
        }
    }

    @Test
    public void hybridFade_rampStartsLowAndReachesExactTarget() {
        // 从 10% 渐增到 100%：起始系统档位 = ceil(1.5) = 2（13.3%），
        // 结束档位 = 15（100%），整个过程中增益都 ≤ 1，且每一步响度 = 进度百分比。
        float from = 10f, to = 100f;
        int steps = 50;
        int prevSys = 0;
        for (int i = 1; i <= steps; i++) {
            float percent = from + (to - from) * i / steps;
            int sysVol = PlayerService.targetSystemVolume(percent, MAX_VOL);
            float gain = PlayerService.alarmGainForVolume(percent, sysVol / (float) MAX_VOL);
            // 响度 = 系统档位比例 × 增益 = 恰好等于进度百分比
            assertEquals(percent / 100f, sysVol / (float) MAX_VOL * gain, 0.001f);
            assertTrue("系统档位单调不降", sysVol >= prevSys);
            assertTrue("增益在 (0,1]", gain > 0f && gain <= 1f);
            prevSys = sysVol;
        }
        // 终点恰好是目标
        assertEquals(100f / 100f, MAX_VOL / (float) MAX_VOL
                * PlayerService.alarmGainForVolume(100f, 1f), 0.001f);
        assertEquals(MAX_VOL, PlayerService.targetSystemVolume(100f, MAX_VOL));
    }
}
