package io.androllm.core.appatlas.search

/**
 * Small multilingual concept dictionary (English, Russian, Azerbaijani and
 * their Latin transliteration). Maps the user's words to canonical action
 * concepts ("search", "send", "settings"…). Used to
 * - match a request against action titles / URIs / UI vocabulary, and
 * - derive a learned-path task key that contains NO user words
 *   ("send a message to Ali saying hi" → "message+send").
 */
object Concepts {

    val STEMS: Map<String, List<String>> = linkedMapOf(
        "search" to listOf("search", "find", "look for", "lookup", "найд", "найти", "поиск", "ищи", "искать", "поищи", "axtar", "poisk", "naydi", "naiti"),
        "send" to listOf("send", "отправ", "пошли", "послать", "göndər", "gonder", "otprav", "poshli"),
        "message" to listOf("message", "сообщен", "напиши", "написать", "chat", "чат", "mesaj", "yaz", "soobshch", "napishi", "inbox"),
        "call" to listOf("call", "позвон", "звонок", "набери", "zəng", "zeng", "pozvoni"),
        "play" to listOf("play", "включи", "воспроизв", "поставь", "слушать", "послушать", "oxut", "çal", "oynat", "vklyuchi", "postav"),
        "pause" to listOf("pause", "пауз", "останови", "dayandır", "dayandir", "pauza"),
        "settings" to listOf("settings", "setting", "preferences", "настройк", "параметр", "ayar", "nastroyk", "nastroik"),
        "order" to listOf("order", "заказ", "sifariş", "sifaris", "zakaz", "checkout"),
        "cart" to listOf("cart", "basket", "корзин", "səbət", "sebet", "korzin"),
        "taxi" to listOf("taxi", "такси", "taksi"),
        "navigate" to listOf("navigate", "navigation", "route", "directions", "маршрут", "навиг", "проложи", "доехать", "yol", "marşrut", "marshrut", "naviq"),
        "map" to listOf("map", "карт", "xəritə", "xerite", "kart"),
        "photo" to listOf("photo", "camera", "picture", "фото", "камер", "снимок", "şəkil", "sekil", "foto", "kamer"),
        "scan" to listOf("scan", "qr", "скан", "skan"),
        "share" to listOf("share", "поделись", "поделиться", "paylaş", "paylas"),
        "pay" to listOf("pay", "payment", "оплат", "заплат", "платеж", "платёж", "ödə", "odenis", "oplat"),
        "profile" to listOf("profile", "account", "профил", "аккаунт", "hesab", "profil", "akkaunt"),
        "history" to listOf("history", "recent", "истори", "tarixç", "tarixc", "istori"),
        "notifications" to listOf("notification", "уведомл", "bildiriş", "bildiris", "uvedoml"),
        "new" to listOf("new", "create", "compose", "новый", "новое", "новую", "созда", "yeni", "yarat", "novy", "sozda"),
        "translate" to listOf("translate", "translation", "перевед", "переводчик", "tərcümə", "tercume", "perevod"),
        "music" to listOf("music", "song", "track", "музык", "песн", "трек", "musiqi", "mahnı", "mahni", "muzyk", "pesn"),
        "video" to listOf("video", "видео", "ролик", "rolik"),
        "download" to listOf("download", "скача", "загруз", "yüklə", "yukle", "skacha"),
        "favorites" to listOf("favorite", "favourite", "saved", "избран", "сохранен", "seçilmiş", "secilmis", "izbran"),
        "library" to listOf("library", "библиотек", "kitabxana", "playlist", "плейлист")
    )

    /** Concepts present in [text] (lower-cased substring match on stems). */
    fun of(text: String): Set<String> {
        val t = " " + text.lowercase().replace(Regex("\\s+"), " ") + " "
        return STEMS.filter { (_, stems) -> stems.any { it in t } }.keys
    }

    /**
     * Learned-path key for a task description: up to 3 concepts, sorted,
     * joined with '+'. Null when the text has no known action concept — then
     * nothing is recorded (we never store the user's own words).
     */
    fun taskKey(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val c = of(text).sorted().take(3)
        return if (c.isEmpty()) null else c.joinToString("+")
    }

    /** Stems of [concept] (empty for unknown). */
    fun stems(concept: String): List<String> = STEMS[concept].orEmpty()
}
