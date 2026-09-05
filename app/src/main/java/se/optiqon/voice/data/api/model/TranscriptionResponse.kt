package se.optiqon.voice.data.api.model

import com.google.gson.annotations.SerializedName

data class TranscriptionResponse(
    @SerializedName("text") val text: String
)
