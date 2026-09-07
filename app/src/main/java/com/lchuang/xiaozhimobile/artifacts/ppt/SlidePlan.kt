package com.lchuang.xiaozhimobile.artifacts.ppt

data class SlidePlan(
    val index: Int,
    val title: String,
    val body: List<String>,
    val media: List<SlideMedia>,
)

data class PresentationPlan(
    val title: String,
    val slides: List<SlidePlan>,
)

sealed interface PptPlanResult {
    data class Planned(val plan: PresentationPlan) : PptPlanResult

    data class Rejected(val reason: String) : PptPlanResult
}
