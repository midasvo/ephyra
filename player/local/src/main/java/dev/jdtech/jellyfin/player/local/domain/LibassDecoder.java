package dev.jdtech.jellyfin.player.local.domain;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.text.Cue;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegSubtitleDecoder;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Spike (#668): nextlib's libass decoder, which is internal to its module in Kotlin. Java does not
 * know that modifier, so this class is how {@link AsyncLibassRenderer} reaches it.
 */
@SuppressWarnings("KotlinInternalInJava")
final class LibassDecoder implements AutoCloseable {
    private final FfmpegSubtitleDecoder decoder;

    LibassDecoder(Context context, Format format, @Nullable File fontsDirectory) {
        decoder = new FfmpegSubtitleDecoder(context, format, fontsDirectory);
    }

    boolean decode(ByteBuffer data, long timeUs, long offsetUs) {
        return decoder.decode(data, timeUs, offsetUs);
    }

    /** {@code null} when the picture is the same as the last one. */
    @Nullable
    List<Cue> render(long timeUs, int width, int height) {
        return decoder.render(timeUs, width, height);
    }

    void invalidate() {
        decoder.invalidateOutput();
    }

    @Override
    public void close() {
        decoder.close();
    }
}
