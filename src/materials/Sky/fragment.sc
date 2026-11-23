#ifndef INSTANCING
  $input v_worldPos, v_underwaterRainTimeDay
#endif

#include <bgfx_shader.sh>

#ifndef INSTANCING
  #include <newb/main.sh>

  uniform vec4 TimeOfDay;
  uniform vec4 FogColor;
  uniform vec4 FogAndDistanceControl;
  uniform vec4 CameraPosition;

  SAMPLER2D_AUTOREG(s_noise);

  // ============================================================================
  // CUSTOM AURORA SETTINGS
  // ============================================================================
  #define AURORA_COLOR_1 vec3(7.0, 2.2, 12.0)
  #define AURORA_COLOR_2 vec3(6.0, 16.0, 18.0)
  #define AURORA_BRIGHTNESS 1.0
  #define AURORA_LAYERS 20

  #ifndef AURORA_STYLE
    #define AURORA_STYLE 1
  #endif

  #ifndef AURORA_CONDITION
    #define AURORA_CONDITION 0
  #endif

  float auroraPow2(float x) {
    return x * x;
  }

  float auroraPow1_5(float x) {
    return x * sqrt(max(x, 0.0));
  }

  float auroraClamp01(float x) {
    return clamp(x, 0.0, 1.0);
  }

  float auroraSqrt1(float x) {
    return sqrt(max(x, 0.0));
  }

  vec3 GetAuroraBorealis(
    vec3 viewPos,
    float VdotU,
    float dither,
    float syncedTime,
    float frameTimeCounter,
    vec3 cameraPosition,
    float sunVisibility,
    float rainFactor
  ) {
    // These two optional conditions remain neutral until the corresponding
    // Bedrock biome/moon values are mapped.
    float maxBlindnessDarkness = 0.0;
    float moonPhase = 0.0;
    float inSnowy = 1.0;

    float visibility = auroraSqrt1(auroraClamp01(VdotU * 1.5 - 0.225))
      - sunVisibility
      - rainFactor
      - maxBlindnessDarkness;

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
      vec3 aurora = vec3(0.0, 0.0, 0.0);

      // The sky material already receives a world-oriented view direction.
      vec3 wpos = viewPos;
      wpos.xz /= max(0.0001, wpos.y);

      vec2 cameraPositionM = cameraPosition.xz * 0.0075;
      cameraPositionM.x += syncedTime * 0.04;

      float sampleCountP = float(AURORA_LAYERS + 10);
      float ditherM = dither + 5.0;
      float auroraAnimate = frameTimeCounter * 0.001;

      for (int i = 0; i < AURORA_LAYERS; i++) {
        float current = auroraPow2((float(i) + ditherM) / sampleCountP);

        vec2 planePos = wpos.xz * (0.8 + current) * 11.0 + cameraPositionM;

        #if AURORA_STYLE == 1
          planePos = floor(planePos) * 0.0007;

          float n = texture2D(s_noise, planePos).b;
          n = auroraPow2(auroraPow2(auroraPow2(auroraPow2(
            1.0 - 2.0 * abs(n - 0.5)
          ))));

          n *= auroraPow1_5(
            texture2D(s_noise, planePos * 100.0 + auroraAnimate).b
          );
        #else
          planePos *= 0.0007;

          float n = texture2D(s_noise, planePos).r;
          n = auroraPow2(auroraPow2(auroraPow2(auroraPow2(
            1.0 - 2.0 * abs(n - 0.5)
          ))));

          n *= texture2D(s_noise, planePos * 3.0 + auroraAnimate).b;
          n *= texture2D(s_noise, planePos * 5.0 - auroraAnimate).b;
        #endif

        float currentM = 1.0 - current;
        aurora += n * currentM * mix(
          AURORA_COLOR_1,
          AURORA_COLOR_2,
          auroraPow2(auroraPow2(currentM))
        );
      }

      #if AURORA_STYLE == 1
        aurora *= 1.3;
      #else
        aurora *= 1.8;
      #endif

      aurora *= AURORA_BRIGHTNESS;
      return aurora * visibility / float(AURORA_LAYERS);
    }

    return vec3(0.0, 0.0, 0.0);
  }
#endif

void main() {
  #ifndef INSTANCING
    vec3 viewDir = normalize(v_worldPos);

    nl_environment env;
    env.end = false;
    env.nether = false;
    env.underwater = v_underwaterRainTimeDay.x > 0.5;
    env.rainFactor = v_underwaterRainTimeDay.y;
    env.dayFactor = v_underwaterRainTimeDay.w;
    env.fogCol = FogColor.rgb;
    env = calculateSunParams(env, TimeOfDay.x);

    nl_skycolor skycol = nlOverworldSkyColors(env);

    vec3 skyColor = nlRenderSky(skycol, env, -viewDir, v_underwaterRainTimeDay.z, true);

    // Match the direction convention used by nlRenderSky while keeping the
    // aurora in the upper hemisphere.
    vec3 auroraViewDir = vec3(-viewDir.x, viewDir.y, -viewDir.z);
    float VdotU = clamp(auroraViewDir.y, 0.0, 1.0);
    float dither = fract(
      sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453
    );

    // Fade the custom aurora as the sun rises and during rain.
    if (!env.underwater) {
      skyColor += GetAuroraBorealis(
        auroraViewDir,
        VdotU,
        dither,
        v_underwaterRainTimeDay.z,
        v_underwaterRainTimeDay.z,
        CameraPosition.xyz,
        max(env.dayFactor, 0.0),
        env.rainFactor
      );
    }

    #ifdef NL_SHOOTING_STAR
      skyColor += NL_SHOOTING_STAR*nlRenderShootingStar(viewDir, env.fogCol, v_underwaterRainTimeDay.z);
    #endif
    #ifdef NL_GALAXY_STARS
      skyColor += NL_GALAXY_STARS*nlRenderGalaxy(viewDir, env.fogCol, env, v_underwaterRainTimeDay.z);
    #endif

    skyColor = colorCorrection(skyColor);

    gl_FragColor = vec4(skyColor, 1.0);
  #else
    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
  #endif
}
