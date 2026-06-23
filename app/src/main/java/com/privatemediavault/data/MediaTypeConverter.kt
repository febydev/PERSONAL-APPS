package com.privatemediavault.data

import androidx.room.TypeConverter
import com.privatemediavault.domain.model.MediaType

/**
 * Room type converter for the [MediaType] enum.
 *
 * Persists the enum by its stable name so the stored value is human-readable and
 * resilient to ordinal reordering.
 */
class MediaTypeConverter {

    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String): MediaType = MediaType.valueOf(value)
}
