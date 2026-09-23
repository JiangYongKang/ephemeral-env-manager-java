package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 单个环境的一次回收判定结果，回收可重复执行：已释放时 released=false 且 detail 说明幂等命中。
 */
public record ReclaimOutcome(String name, boolean released, String detail) {
}
