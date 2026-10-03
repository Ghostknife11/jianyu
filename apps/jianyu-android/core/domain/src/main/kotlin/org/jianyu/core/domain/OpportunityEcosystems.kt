package org.jianyu.core.domain

/**
 * Stable machine identifiers for the route an opportunity opens.
 *
 * Providers and Packs may use older English or Chinese aliases. They are
 * normalized before policy and diversity evaluation so aliases cannot appear
 * as different ecosystems. Localization belongs to the client UI.
 */
object OpportunityEcosystems {
    const val EXISTING_INTEREST = "existing-interest"
    const val DIGITAL_GAME = "digital-game"
    const val MEDIA = "media"
    const val READING = "reading"
    const val SPORT = "sport"
    const val MAKING = "making"
    const val FAMILY_LIFE = "family-life"
    const val NATURE = "nature"
    const val TRAVEL = "travel"
    const val PLACE = "place"
    const val PEOPLE = "people"
    const val REAL_WORLD = "real-world"
    const val WORLD_EVENT = "world-event"
    const val DIGITAL_MAKING = "digital-making"
    const val REAL_PROJECT = "real-project"
    const val WORKSHEET = "worksheet"
    const val NOTHING = "nothing"
    const val OTHER = "other"

    val providerIds: Set<String> = setOf(
        EXISTING_INTEREST,
        DIGITAL_GAME,
        MEDIA,
        READING,
        SPORT,
        MAKING,
        FAMILY_LIFE,
        NATURE,
        TRAVEL,
        PLACE,
        PEOPLE,
        REAL_WORLD,
        WORLD_EVENT,
        DIGITAL_MAKING,
        REAL_PROJECT,
        OTHER,
    )

    fun canonical(raw: String): String = when (raw.trim().lowercase()) {
        "existing", "existing-interest", "current-interest", "已有兴趣", "顺着兴趣" -> EXISTING_INTEREST
        "game", "games", "digital-game", "digital-games", "游戏", "电子游戏" -> DIGITAL_GAME
        "media", "film", "video", "audio-visual", "视听", "影视", "影视与内容" -> MEDIA
        "book", "books", "reading", "阅读", "书籍" -> READING
        "sport", "sports", "运动", "体育" -> SPORT
        "making", "maker", "diy", "动手", "动手制作", "动手创造", "家庭实验" -> MAKING
        "family", "family-life", "daily-life", "家庭", "家庭生活", "日常生活" -> FAMILY_LIFE
        "nature", "自然", "自然观察" -> NATURE
        "travel", "trip", "出行", "旅行" -> TRAVEL
        "place", "places", "museum", "exhibition", "offline-culture", "场所", "博物馆", "展览", "线下文化", "场所与展览" -> PLACE
        "people", "person", "social", "身边的人", "真实人物", "人与社交" -> PEOPLE
        "real-world", "real-world-observation", "reality", "现实世界", "现实观察" -> REAL_WORLD
        "world", "world-event", "current-event", "public-event", "正在发生的世界", "公共事件" -> WORLD_EVENT
        "digital-making", "digital-creation", "数字创作" -> DIGITAL_MAKING
        "real-project", "真实项目" -> REAL_PROJECT
        "worksheet", "lesson", "course", "练习", "练习材料", "课程" -> WORKSHEET
        "nothing", "留白", "什么都不做" -> NOTHING
        "other", "其他", "其他入口" -> OTHER
        else -> OTHER
    }
}
