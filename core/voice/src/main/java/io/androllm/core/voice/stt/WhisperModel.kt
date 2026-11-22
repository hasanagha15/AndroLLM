package io.androllm.core.voice.stt

/**
 * A downloadable whisper.cpp model (pre-converted ggml format).
 *
 * Sizes and SHA-256 checksums are the Git-LFS object ids of the files in
 * huggingface.co/ggerganov/whisper.cpp at the pinned revision
 * [WhisperModels.HF_REVISION] (verified 2026-11-22 via the HF tree API and
 * `X-Linked-ETag` / `X-Linked-Size` headers). The download URL is pinned to
 * that revision so an upstream re-upload can never silently change the bytes
 * (the SHA check in [WhisperModelManager] is enforced).
 */
data class WhisperModel(
    val id: String,
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val multilingual: Boolean,
    /** 0 = low-end, 1 = mid-range, 2 = high-end (Snapdragon 8 Gen 3+). */
    val tier: Int
) {
    val downloadUrl: String
        get() = "https://huggingface.co/${WhisperModels.HF_REPO}/resolve/${WhisperModels.HF_REVISION}/$fileName"

    val sizeLabel: String
        get() = when {
            sizeBytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", sizeBytes / (1024.0 * 1024 * 1024))
            else -> String.format("%d MB", sizeBytes / (1024 * 1024))
        }
}

object WhisperModels {

    const val HF_REPO = "ggerganov/whisper.cpp"

    /** Commit of [HF_REPO] the catalog below was verified against. */
    const val HF_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"

    val ALL: List<WhisperModel> = listOf(
        WhisperModel(
            id = "tiny.en", fileName = "ggml-tiny.en.bin", displayName = "Tiny (English)",
            sizeBytes = 77_704_715, sha256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f",
            multilingual = false, tier = 0
        ),
        WhisperModel(
            id = "tiny", fileName = "ggml-tiny.bin", displayName = "Tiny (Multilingual)",
            sizeBytes = 77_691_713, sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            multilingual = true, tier = 0
        ),
        WhisperModel(
            id = "base.en", fileName = "ggml-base.en.bin", displayName = "Base (English)",
            sizeBytes = 147_964_211, sha256 = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002",
            multilingual = false, tier = 1
        ),
        WhisperModel(
            id = "base", fileName = "ggml-base.bin", displayName = "Base (Multilingual)",
            sizeBytes = 147_951_465, sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            multilingual = true, tier = 1
        ),
        WhisperModel(
            id = "base-q8_0", fileName = "ggml-base-q8_0.bin", displayName = "Base Q8 (Multilingual)",
            sizeBytes = 81_768_585, sha256 = "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9",
            multilingual = true, tier = 0
        ),
        WhisperModel(
            id = "small.en", fileName = "ggml-small.en.bin", displayName = "Small (English)",
            sizeBytes = 487_614_201, sha256 = "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d",
            multilingual = false, tier = 1
        ),
        WhisperModel(
            id = "small", fileName = "ggml-small.bin", displayName = "Small (Multilingual)",
            sizeBytes = 487_601_967, sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
            multilingual = true, tier = 1
        ),
        WhisperModel(
            id = "small-q5_1", fileName = "ggml-small-q5_1.bin", displayName = "Small Q5 (Multilingual)",
            sizeBytes = 190_085_487, sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
            multilingual = true, tier = 1
        ),
        WhisperModel(
            id = "small-q8_0", fileName = "ggml-small-q8_0.bin", displayName = "Small Q8 (Multilingual)",
            sizeBytes = 264_464_607, sha256 = "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f",
            multilingual = true, tier = 1
        ),
        WhisperModel(
            id = "medium.en", fileName = "ggml-medium.en.bin", displayName = "Medium (English)",
            sizeBytes = 1_533_774_781, sha256 = "cc37e93478338ec7700281a7ac30a10128929eb8f427dda2e865faa8f6da4356",
            multilingual = false, tier = 2
        ),
        WhisperModel(
            id = "large-v3-turbo-q5_0", fileName = "ggml-large-v3-turbo-q5_0.bin", displayName = "Large v3 Turbo Q5",
            sizeBytes = 574_041_195, sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
            multilingual = true, tier = 2
        ),
        WhisperModel(
            id = "large-v3-turbo", fileName = "ggml-large-v3-turbo.bin", displayName = "Large v3 Turbo",
            sizeBytes = 1_624_555_275, sha256 = "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69",
            multilingual = true, tier = 2
        )
    )

    fun byId(id: String): WhisperModel? = ALL.firstOrNull { it.id == id }

    /**
     * Recommended default model for the device. Multilingual by default in
     * this fork (Russian voice): small Q8 on >= 6 GB RAM, base Q8 otherwise.
     * Language-aware selection lives in
     * [io.androllm.core.voice.lang.VoiceLanguagePolicy.recommendedModel].
     */
    fun recommendedForDevice(totalRamGb: Long): WhisperModel =
        io.androllm.core.voice.lang.VoiceLanguagePolicy.recommendedModel(
            io.androllm.core.voice.lang.VoiceLanguage.DEFAULT, totalRamGb
        )
}
