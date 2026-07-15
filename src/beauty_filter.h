#ifndef BEAUTY_FILTER_H
#define BEAUTY_FILTER_H

#ifdef __APPLE__
#include <OpenGLES/ES2/gl.h>
#include <OpenGLES/ES2/glext.h>
#else
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <EGL/egl.h>
#endif

class BeautyFilter {
public:
    BeautyFilter();
    ~BeautyFilter();

    void init(int width, int height);
    void setIntensity(float intensity);
    void setFaceBounds(float minX, float minY, float maxX, float maxY);
    GLuint processTexture(GLuint inputTextureId, int width, int height);
    void release();

private:
    GLuint compileShader(GLenum type, const char* source);
    void setupFBO(int width, int height);

    GLuint mProgram;
    GLuint mPositionLoc;
    GLuint mTexCoordLoc;
    GLuint mTextureLoc;
    GLuint mIntensityLoc;
    GLuint mTexelSizeLoc;
    GLuint mFaceBoundsLoc;

    GLuint mFBO;
    GLuint mOutputTextures[3]; // Triple output textures for tearing prevention
    int mTextureIndex;
    int mWidth;
    int mHeight;
    float mIntensity;
    float mMinX, mMinY, mMaxX, mMaxY;
    bool mInitialized;

#ifndef __APPLE__
    EGLContext mEglContext;
#endif
};

#endif // BEAUTY_FILTER_H
