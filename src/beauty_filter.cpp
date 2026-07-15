#include "beauty_filter.h"
#include <cstdio>
#include <cmath>

#if defined(__ANDROID__)
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "BeautyFilter", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "BeautyFilter", __VA_ARGS__)
#else
#define LOGI(...) printf(__VA_ARGS__)
#define LOGE(...) printf(__VA_ARGS__)
#endif

const char* VERTEX_SHADER_SRC = 
    "attribute vec4 position;\n"
    "attribute vec2 texCoord;\n"
    "varying vec2 vTextureCoord;\n"
    "void main() {\n"
    "    vTextureCoord = texCoord;\n"
    "    gl_Position = position;\n"
    "}\n";

const char* FRAGMENT_SHADER_SRC = 
    "precision mediump float;\n"
    "varying vec2 vTextureCoord;\n"
    "uniform sampler2D sTexture;\n"
    "uniform float uIntensity;\n"
    "uniform vec2 uTexelSize;\n"
    "uniform vec4 uFaceBounds;\n" // x: minX, y: minY, z: maxX, w: maxY
    "\n"
    "// HSV skin color detection\n"
    "float getSkinWeight(vec3 rgb) {\n"
    "    float r = rgb.r;\n"
    "    float g = rgb.g;\n"
    "    float b = rgb.b;\n"
    "    float maxVal = max(r, max(g, b));\n"
    "    float minVal = min(r, min(g, b));\n"
    "    float delta = maxVal - minVal;\n"
    "    \n"
    "    float h = 0.0;\n"
    "    if (delta > 0.0) {\n"
    "        if (maxVal == r) {\n"
    "            h = (g - b) / delta;\n"
    "        } else if (maxVal == g) {\n"
    "            h = 2.0 + (b - r) / delta;\n"
    "        } else {\n"
    "            h = 4.0 + (r - g) / delta;\n"
    "        }\n"
    "        h *= 60.0;\n"
    "        if (h < 0.0) h += 360.0;\n"
    "    }\n"
    "    \n"
    "    float s = maxVal > 0.0 ? delta / maxVal : 0.0;\n"
    "    float v = maxVal;\n"
    "    \n"
    "    // Skin HSV ranges: H in [0, 45] or [340, 360], S in [0.15, 0.7], V in [0.3, 1.0]\n"
    "    float hWeight = 0.0;\n"
    "    if (h <= 45.0) {\n"
    "        hWeight = smoothstep(0.0, 8.0, h);\n"
    "    } else if (h >= 340.0) {\n"
    "        hWeight = smoothstep(360.0, 350.0, h);\n"
    "    }\n"
    "    float sWeight = smoothstep(0.12, 0.20, s) * (1.0 - smoothstep(0.65, 0.75, s));\n"
    "    float vWeight = smoothstep(0.25, 0.35, v);\n"
    "    \n"
    "    return hWeight * sWeight * vWeight;\n"
    "}\n"
    "\n"
    "void main() {\n"
    "    vec4 centerColor = texture2D(sTexture, vTextureCoord);\n"
    "    if (uIntensity <= 0.0) {\n"
    "        gl_FragColor = centerColor;\n"
    "        return;\n"
    "    }\n"
    "    \n"
    "    // Optimization & Bounding Box Check:\n"
    "    // If no face is detected (bounds are zero/empty), or if coordinates lie outside the face bounds,\n"
    "    // exit immediately and return the center pixel color. This saves 25 texture fetches (GPU bypass).\n"
    "    if (uFaceBounds.z <= 0.0 && uFaceBounds.w <= 0.0) {\n"
    "        gl_FragColor = centerColor;\n"
    "        return;\n"
    "    }\n"
    "    \n"
    "    if (vTextureCoord.x < uFaceBounds.x || vTextureCoord.x > uFaceBounds.z ||\n"
    "        vTextureCoord.y < uFaceBounds.y || vTextureCoord.y > uFaceBounds.w) {\n"
    "        gl_FragColor = centerColor;\n"
    "        return;\n"
    "    }\n"
    "    \n"
    "    float totalWeight = 0.0;\n"
    "    vec3 sumColor = vec3(0.0);\n"
    "    \n"
    "    float sigmaD = 3.0; // Spatial standard deviation\n"
    "    float sigmaR = 0.12 * uIntensity; // Color standard deviation scaled by intensity\n"
    "    \n"
    "    // 5x5 Bilateral Filter\n"
    "    for (int i = -2; i <= 2; i++) {\n"
    "        for (int j = -2; j <= 2; j++) {\n"
    "            vec2 offset = vec2(float(i), float(j)) * uTexelSize;\n"
    "            vec4 neighborColor = texture2D(sTexture, vTextureCoord + offset);\n"
    "            \n"
    "            float distSqr = float(i * i + j * j);\n"
    "            float spatialWeight = exp(-distSqr / (2.0 * sigmaD * sigmaD));\n"
    "            \n"
    "            float colorDistSqr = dot(neighborColor.rgb - centerColor.rgb, neighborColor.rgb - centerColor.rgb);\n"
    "            float rangeWeight = exp(-colorDistSqr / (2.0 * sigmaR * sigmaR));\n"
    "            \n"
    "            float weight = spatialWeight * rangeWeight;\n"
    "            sumColor += neighborColor.rgb * weight;\n"
    "            totalWeight += weight;\n"
    "        }\n"
    "    }\n"
    "    \n"
    "    vec3 blurred = sumColor / totalWeight;\n"
    "    float skinWeight = getSkinWeight(centerColor.rgb);\n"
    "    \n"
    "    // Apply skin smoothing filter only to skin area inside the bounding box\n"
    "    vec3 finalColor = mix(centerColor.rgb, blurred, skinWeight * uIntensity);\n"
    "    gl_FragColor = vec4(finalColor, centerColor.a);\n"
    "}\n";

BeautyFilter::BeautyFilter() 
    : mProgram(0), mPositionLoc(0), mTexCoordLoc(0), mTextureLoc(0), 
      mIntensityLoc(0), mTexelSizeLoc(0), mFaceBoundsLoc(0), mFBO(0),
      mTextureIndex(0), mWidth(0), mHeight(0), mIntensity(0.5f),
      mMinX(0.0f), mMinY(0.0f), mMaxX(0.0f), mMaxY(0.0f), mInitialized(false) {
    for (int i = 0; i < 3; i++) {
        mOutputTextures[i] = 0;
    }
#ifndef __APPLE__
    mEglContext = EGL_NO_CONTEXT;
#endif
}

BeautyFilter::~BeautyFilter() {
    release();
}

GLuint BeautyFilter::compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog);
            LOGE("Error compiling shader:\n%s\n", infoLog);
            delete[] infoLog;
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

void BeautyFilter::setupFBO(int width, int height) {
    if (mFBO != 0 && mWidth == width && mHeight == height) {
        return;
    }
    
    if (mFBO != 0) {
        glDeleteFramebuffers(1, &mFBO);
        glDeleteTextures(3, mOutputTextures);
        mFBO = 0;
        for (int i = 0; i < 3; i++) {
            mOutputTextures[i] = 0;
        }
    }

    glGenFramebuffers(1, &mFBO);
    glBindFramebuffer(GL_FRAMEBUFFER, mFBO);

    glGenTextures(3, mOutputTextures);
    for (int i = 0; i < 3; i++) {
        glBindTexture(GL_TEXTURE_2D, mOutputTextures[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    // Bind initial texture to avoid incomplete framebuffer issues during setup
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mOutputTextures[0], 0);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Failed to set up complete Framebuffer Object (status: %d)", status);
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    
    mWidth = width;
    mHeight = height;
    mTextureIndex = 0;
}

void BeautyFilter::init(int width, int height) {
#ifndef __APPLE__
    mEglContext = eglGetCurrentContext();
#endif

    if (mInitialized) {
        setupFBO(width, height);
        return;
    }

    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER_SRC);
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC);

    if (vertexShader == 0 || fragmentShader == 0) {
        LOGE("Shader compilation failed. Cannot initialize BeautyFilter.");
        return;
    }

    mProgram = glCreateProgram();
    glAttachShader(mProgram, vertexShader);
    glAttachShader(mProgram, fragmentShader);
    glLinkProgram(mProgram);

    GLint linked = 0;
    glGetProgramiv(mProgram, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(mProgram, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetProgramInfoLog(mProgram, infoLen, nullptr, infoLog);
            LOGE("Error linking shader program:\n%s\n", infoLog);
            delete[] infoLog;
        }
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        glDeleteProgram(mProgram);
        mProgram = 0;
        return;
    }

    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    mPositionLoc = glGetAttribLocation(mProgram, "position");
    mTexCoordLoc = glGetAttribLocation(mProgram, "texCoord");
    mTextureLoc = glGetUniformLocation(mProgram, "sTexture");
    mIntensityLoc = glGetUniformLocation(mProgram, "uIntensity");
    mTexelSizeLoc = glGetUniformLocation(mProgram, "uTexelSize");
    mFaceBoundsLoc = glGetUniformLocation(mProgram, "uFaceBounds");

    setupFBO(width, height);
    mInitialized = true;
    LOGI("BeautyFilter successfully initialized for resolution: %dx%d", width, height);
}

void BeautyFilter::setIntensity(float intensity) {
    if (intensity < 0.0f) intensity = 0.0f;
    if (intensity > 1.0f) intensity = 1.0f;
    mIntensity = intensity;
}

void BeautyFilter::setFaceBounds(float minX, float minY, float maxX, float maxY) {
    mMinX = minX;
    mMinY = minY;
    mMaxX = maxX;
    mMaxY = maxY;
}

GLuint BeautyFilter::processTexture(GLuint inputTextureId, int width, int height) {
#ifndef __APPLE__
    EGLContext currentContext = eglGetCurrentContext();
    if (mInitialized && currentContext != mEglContext) {
        LOGI("BeautyFilter: EGL context changed/lost! Invalidating old handles and re-initializing...");
        mProgram = 0;
        mFBO = 0;
        for (int i = 0; i < 3; i++) {
            mOutputTextures[i] = 0;
        }
        mInitialized = false;
        
        init(width, height);
    }
#endif

    if (!mInitialized) {
        init(width, height);
        if (!mInitialized) return inputTextureId;
    }

    setupFBO(width, height);

    // Cycle through triple textures to prevent screen tearing/overwrites (Double/Triple Buffering)
    mTextureIndex = (mTextureIndex + 1) % 3;
    GLuint outputTex = mOutputTextures[mTextureIndex];

    GLint prevFBO;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFBO);
    GLint prevViewport[4];
    glGetIntegerv(GL_VIEWPORT, prevViewport);

    glBindFramebuffer(GL_FRAMEBUFFER, mFBO);
    glViewport(0, 0, width, height);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outputTex, 0);

    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(mProgram);

    const GLfloat quadVertices[] = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };
    const GLfloat quadTexCoords[] = {
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    };

    glVertexAttribPointer(mPositionLoc, 2, GL_FLOAT, GL_FALSE, 0, quadVertices);
    glEnableVertexAttribArray(mPositionLoc);

    glVertexAttribPointer(mTexCoordLoc, 2, GL_FLOAT, GL_FALSE, 0, quadTexCoords);
    glEnableVertexAttribArray(mTexCoordLoc);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, inputTextureId);
    glUniform1i(mTextureLoc, 0);

    glUniform1f(mIntensityLoc, mIntensity);
    glUniform2f(mTexelSizeLoc, 1.0f / (float)width, 1.0f / (float)height);
    glUniform4f(mFaceBoundsLoc, mMinX, mMinY, mMaxX, mMaxY);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(mPositionLoc);
    glDisableVertexAttribArray(mTexCoordLoc);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);

    glBindFramebuffer(GL_FRAMEBUFFER, prevFBO);
    glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);

    return outputTex;
}

void BeautyFilter::release() {
    if (mProgram != 0) {
#ifndef __APPLE__
        if (eglGetCurrentContext() == mEglContext) {
            glDeleteProgram(mProgram);
        }
#else
        glDeleteProgram(mProgram);
#endif
        mProgram = 0;
    }
    if (mFBO != 0) {
#ifndef __APPLE__
        if (eglGetCurrentContext() == mEglContext) {
            glDeleteFramebuffers(1, &mFBO);
            glDeleteTextures(3, mOutputTextures);
        }
#else
        glDeleteFramebuffers(1, &mFBO);
        glDeleteTextures(3, mOutputTextures);
#endif
        mFBO = 0;
        for (int i = 0; i < 3; i++) {
            mOutputTextures[i] = 0;
        }
    }
    mInitialized = false;
#ifndef __APPLE__
    mEglContext = EGL_NO_CONTEXT;
#endif
    LOGI("BeautyFilter resources released.");
}
