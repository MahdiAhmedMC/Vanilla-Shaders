#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;
uniform float time;
uniform vec2 touch;
uniform sampler2D noise; // Renombrado de pp a noise

#define u_resolution resolution
#define u_mouse touch
#define u_time time

#define CAM_FOV 1.9
#define SPHERE_SIZE 0.4

// ============================================================================
// CONFIGURACIÓN PERSONALIZABLE DE LA AURORA
// ============================================================================
// Colores base de la aurora (RGB)
#define AURORA_COLOR_1 vec3(7.0, 2.2, 12.0)  // Color base / inferior
#define AURORA_COLOR_2 vec3(6.0, 16.0, 18.0) // Color secundario / superior

// Multiplicador de brillo global
#define AURORA_BRIGHTNESS 1.0

// Número de capas para ajustar el rendimiento:
// - 5 a 8:  Rendimiento muy alto (dispositivos débiles)
// - 10:     Valor original
// - 15 a 20: Mayor calidad visual (requiere mayor procesamiento de GPU)
#define AURORA_LAYERS 20
// ============================================================================

#ifndef AURORA_STYLE
#define AURORA_STYLE 1
#endif

#ifndef AURORA_CONDITION
#define AURORA_CONDITION 0
#endif

// Funciones auxiliares de compatibilidad GLSL
float pow2(float x) { return x * x; }
float pow1_5(float x) { return x * sqrt(x); }
float clamp01(float x) { return clamp(x, 0.0, 1.0); }
float sqrt1(float x) { return sqrt(max(0.0, x)); }

// Variables de condición atmosférica
float sunVisibility = 0.0;
float rainFactor = 0.0;
float maxBlindnessDarkness = 0.0;
float moonPhase = 0.0;
float inSnowy = 1.0;
vec3 cameraPosition = vec3(0.0);

// --- FUNCIÓN DE AURORA BOREAL ---
vec3 GetAuroraBorealis(vec3 viewPos, float VdotU, float dither) {
    float syncedTime = u_time;
    float frameTimeCounter = u_time;
    mat3 gbufferModelViewInverse = mat3(1.0);

    float visibility = sqrt1(clamp01(VdotU * 1.5 - 0.225)) - sunVisibility - rainFactor - maxBlindnessDarkness;
    visibility *= 1.0 - VdotU * 0.9;

    #if AURORA_CONDITION == 1 || AURORA_CONDITION == 3
        visibility -= moonPhase;
    #endif
    #if AURORA_CONDITION == 2 || AURORA_CONDITION == 3
        visibility *= inSnowy;
    #endif
    #if AURORA_CONDITION == 4
        visibility = max(visibility * inSnowy, visibility - moonPhase);
    #endif

    if (visibility > 0.0) {
        vec3 aurora = vec3(0.0);

        vec3 wpos = gbufferModelViewInverse * viewPos;
        wpos.xz /= max(0.0001, wpos.y);
        vec2 cameraPositionM = cameraPosition.xz * 0.0075;
        cameraPositionM.x += syncedTime * 0.04;

        int sampleCount = AURORA_LAYERS;
        int sampleCountP = sampleCount + 10;

        float ditherM = dither + 5.0;
        float auroraAnimate = frameTimeCounter * 0.001;

        // Bucle dinámico usando AURORA_LAYERS
        for (int i = 0; i < AURORA_LAYERS; i++) {
            float current = pow2((float(i) + ditherM) / float(sampleCountP));

            vec2 planePos = wpos.xz * (0.8 + current) * 11.0 + cameraPositionM;
            #if AURORA_STYLE == 1
                planePos = floor(planePos) * 0.0007;

                float n = texture2D(noise, planePos).b;
                n = pow2(pow2(pow2(pow2(1.0 - 2.0 * abs(n - 0.5)))));

                n *= pow1_5(texture2D(noise, planePos * 100.0 + auroraAnimate).b);
            #else
                planePos *= 0.0007;

                float n = texture2D(noise, planePos).r;
                n = pow2(pow2(pow2(pow2(1.0 - 2.0 * abs(n - 0.5)))));

                n *= texture2D(noise, planePos * 3.0 + auroraAnimate).b;
                n *= texture2D(noise, planePos * 5.0 - auroraAnimate).b;
            #endif

            float currentM = 1.0 - current;
            aurora += n * currentM * mix(AURORA_COLOR_1, AURORA_COLOR_2, pow2(pow2(currentM)));
        }

        #if AURORA_STYLE == 1
            aurora *= 1.3;
        #else
            aurora *= 1.8;
        #endif

        // Aplica el control global de brillo
        aurora *= AURORA_BRIGHTNESS;

        return aurora * visibility / float(sampleCount);
    }

    return vec3(0.0);
}

vec3 renderWorld(vec3 vdir) {
    float VdotU = clamp(vdir.y, 0.0, 1.0);
    vec3 skyColor = mix(vec3(0.005, 0.01, 0.02), vec3(0.02, 0.05, 0.1), VdotU);

    float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);

    vec3 auroraColor = GetAuroraBorealis(vdir, VdotU, dither);

    return skyColor + auroraColor;
}

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / u_resolution.x;

    vec2 m = u_mouse / u_resolution;
    if (u_mouse.x <= 0.01 && u_mouse.y <= 0.01) {
        m = vec2(0.5, 0.45);
    }

    float yaw   = (m.x - 0.5) * 6.2831;
    float pitch = (m.y - 0.5) * 1.5707;

    vec3 vdir = normalize(vec3(uv.xy, 1.0 / tan(0.5 * CAM_FOV)));

    float cp = cos(pitch), sp = sin(pitch);
    vdir.yz = mat2(cp, -sp, sp, cp) * vdir.yz;

    float cy = cos(yaw), sy = sin(yaw);
    vdir.xz = mat2(cy, -sy, sy, cy) * vdir.xz;

    vec2 st = gl_FragCoord.xy / (u_resolution.x * SPHERE_SIZE) - 1.4;
    bool f = st.x > 1.0;
    if (f) st.x -= 2.3;

    float d = dot(st, st);
    if (d < 1.0) {
        vdir = vec3(st, sqrt(1.0 - d));
        if (f) vdir = vdir.xzy;
    }

    vec3 color = renderWorld(vdir);

    color *= (1.0 + color * 0.063) / (1.0 + color);

    gl_FragColor = vec4(color, 1.0);
}
