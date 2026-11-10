// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.opengl.GLES20;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Blur the OEM still-wallpaper texture, so material consumers sample the same pixels. */
final class KeepWallpaperBlur {
    private static final Pattern SAMPLE = Pattern.compile("\\b(texture2D|texture)\\s*\\(\\s*(\\w+)\\s*,\\s*(\\w+(?:\\.[xyzwstpq]{2})?)\\s*\\)");
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(");
    private static volatile float amount;
    private static final int[] program = new int[1];
    private static int lastProgram, location = -1;
    static boolean drawing;
    static boolean active() { return amount > 0f; }

    static String shader(String source) {
        Matcher sample = SAMPLE.matcher(source);
        if (!sample.find()) return source;
        String texture = sample.group(1);
        String sampler = sample.group(2);
        if (!Pattern.compile("uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?sampler2D\\s+" + Pattern.quote(sampler) + "\\s*;").matcher(source).find()) return source;
        String patched = sample.replaceFirst(Matcher.quoteReplacement("hcKeepSample(" + sampler + ", " + sample.group(3) + ")"));
        Matcher main = MAIN.matcher(patched);
        if (!main.find()) return source;
        // Unknown shaders with sampling helpers before main keep their original source.
        if (sample.start() < main.start()) return source;
        String function = "uniform vec2 uHcKeepBlur;\n"
                + "vec4 hcKeepSample(sampler2D tex, vec2 uv) {\n"
                + "  if (uHcKeepBlur.x <= 0.0) return " + texture + "(tex, uv);\n"
                + "  vec2 stepUv = uHcKeepBlur;\n"
                + "  vec4 sum = vec4(0.0); float total = 0.0;\n"
                + "  for (int x = -3; x <= 3; x++) { for (int y = -3; y <= 3; y++) {\n"
                + "    float weight = exp(-float(x*x+y*y)/4.0);\n"
                + "    sum += " + texture + "(tex, clamp(uv + vec2(float(x),float(y))*stepUv, vec2(0.0), vec2(1.0))) * weight;\n"
                + "    total += weight;\n"
                + "  }} return sum / total;\n}\n";
        return patched.substring(0, main.start()) + function + patched.substring(main.start());
    }

    static void amount(float value) {
        amount = Float.isFinite(value) ? Math.max(0f, Math.min(100f, value)) : 0f;
        WallpaperProbe.videoFrame();
    }

    static void apply(int width, int height) {
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, program, 0);
        if (program[0] != lastProgram) {
            lastProgram = program[0];
            location = lastProgram == 0 ? -1 : GLES20.glGetUniformLocation(lastProgram, "uHcKeepBlur");
        }
        float step = drawing ? amount * .2f : 0f;
        if (location >= 0) GLES20.glUniform2f(location,
                step / Math.max(1, width), step / Math.max(1, height));
    }
}
