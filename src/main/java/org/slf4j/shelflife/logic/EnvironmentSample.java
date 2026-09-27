package org.slf4j.shelflife.logic;

/**
 * 某一次环境采样的完整结果：算倍率用的输入、最终倍率，以及是否走了 {@code rate_override}。
 *
 * <p>之所以把"输入"也带上，是为了让高级提示框能**把公式连同实际数字一起显示出来** ——
 * 只报一个结果数字的话，"为什么是这个倍率"只能靠猜。
 *
 * <p>这个结构也会被塞进网络包发给客户端：客户端不知道打开的容器在世界哪个位置，
 * 更不知道数据包给那个容器写了什么修正，所以这些数只能由服务端算好送过去。
 */
public record EnvironmentSample(float temperature, float humidity, float rate, float rateOverride) {

    /**
     * 表示"没有 rate_override"。
     *
     * <p>用负数当哨兵是安全的：{@code EnvironmentSampler} 本来就拒绝负的 override
     * （负值会掉回温度路径），所以负值永远不会是"有效的 override"。
     */
    public static final float NO_OVERRIDE = -1.0F;

    public boolean hasOverride() {
        return rateOverride >= 0.0F;
    }
}
