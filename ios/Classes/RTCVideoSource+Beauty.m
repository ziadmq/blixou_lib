#import <objc/runtime.h>
#import <WebRTC/WebRTC.h>
#import <OpenGLES/ES2/gl.h>
#import <OpenGLES/ES2/glext.h>
#import <Vision/Vision.h>

// Forward declarations of C++ FFI functions
int init_beauty_filter(int width, int height);
void set_beauty_intensity(int filter_id, float intensity);
void set_face_bounds(int filter_id, float min_x, float min_y, float max_x, float max_y);
int process_texture(int filter_id, int input_texture_id, int width, int height);
void release_beauty_filter(int filter_id);

// Global settings
static float g_beauty_intensity = 0.5f;
static BOOL g_beauty_enabled = NO;

// Global OpenGL resources for iOS
static EAGLContext *g_glContext = nil;
static CVOpenGLESTextureCacheRef g_textureCache = nil;
static int g_filterId = 0;
static int g_width = 0;
static int g_height = 0;
static GLuint g_fbo = 0;

// Vision Face Detection resources
static VNSequenceRequestHandler *g_visionHandler = nil;
static VNDetectFaceRectanglesRequest *g_faceRequest = nil;
static BOOL g_isDetectingFace = NO;
static CGRect g_faceBounds = {0, 0, 0, 0}; // Starts as empty (0, 0, 0, 0) to skip beauty when no face

void set_global_beauty_enabled(int enabled) {
    g_beauty_enabled = (enabled != 0);
}

void set_global_beauty_intensity(float intensity) {
    g_beauty_intensity = intensity;
    if (g_filterId != 0) {
        set_beauty_intensity(g_filterId, intensity);
    }
}

@interface RTCVideoSource (Beauty)
@end

@implementation RTCVideoSource (Beauty)

+ (void)load {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        // Defensive Lookup: Resolve class dynamically to prevent linker/runtime crashes
        Class class = NSClassFromString(@"RTCVideoSource");
        if (!class) {
            NSLog(@"BeautyFilter: RTCVideoSource class not found. Skipping method swizzling.");
            return;
        }

        SEL originalSelector = @selector(capturer:didCaptureVideoFrame:);
        Method originalMethod = class_getInstanceMethod(class, originalSelector);
        if (!originalMethod) {
            NSLog(@"BeautyFilter: capturer:didCaptureVideoFrame: selector not found on RTCVideoSource. Skipping method swizzling.");
            return;
        }

        SEL swizzledSelector = @selector(beauty_capturer:didCaptureVideoFrame:);
        Method swizzledMethod = class_getInstanceMethod([self class], swizzledSelector);
        if (!swizzledMethod) {
            NSLog(@"BeautyFilter: beauty_capturer:didCaptureVideoFrame: custom selector not found. Skipping method swizzling.");
            return;
        }

        // Defensive Swizzling execution wrapped in @try-@catch block
        @try {
            BOOL didAddMethod =
                class_addMethod(class,
                                originalSelector,
                                method_getImplementation(swizzledMethod),
                                method_getTypeEncoding(swizzledMethod));

            if (didAddMethod) {
                class_replaceMethod(class,
                                    swizzledSelector,
                                    method_getImplementation(originalMethod),
                                    method_getTypeEncoding(originalMethod));
            } else {
                method_exchangeImplementations(originalMethod, swizzledMethod);
            }
            NSLog(@"BeautyFilter: Safe Method swizzling on RTCVideoSource successfully completed.");
        } @catch (NSException *exception) {
            NSLog(@"BeautyFilter: Exception occurred during Method swizzling: %@", exception);
        }
    });
}

- (void)beauty_capturer:(RTCVideoCapturer *)capturer didCaptureVideoFrame:(RTCVideoFrame *)frame {
    if (!g_beauty_enabled || g_beauty_intensity <= 0.0f || ![frame.buffer isKindOfClass:NSClassFromString(@"RTCCVPixelBuffer")]) {
        [self beauty_capturer:capturer didCaptureVideoFrame:frame];
        return;
    }

    if (![capturer isKindOfClass:NSClassFromString(@"RTCCameraVideoCapturer")]) {
        [self beauty_capturer:capturer didCaptureVideoFrame:frame];
        return;
    }

    RTCCVPixelBuffer *pixelBuffer = (RTCCVPixelBuffer *)frame.buffer;
    CVPixelBufferRef srcPixelBuffer = pixelBuffer.pixelBuffer;
    if (!srcPixelBuffer) {
        [self beauty_capturer:capturer didCaptureVideoFrame:frame];
        return;
    }

    int w = (int)CVPixelBufferGetWidth(srcPixelBuffer);
    int h = (int)CVPixelBufferGetHeight(srcPixelBuffer);

    static dispatch_once_t glOnceToken;
    dispatch_once(&glOnceToken, ^{
        g_glContext = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES2];
    });

    if (!g_glContext) {
        [self beauty_capturer:capturer didCaptureVideoFrame:frame];
        return;
    }

    EAGLContext *prevContext = [EAGLContext currentContext];
    [EAGLContext setCurrentContext:g_glContext];

    if (!g_textureCache) {
        CVReturn err = CVOpenGLESTextureCacheCreate(kCFAllocatorDefault, NULL, g_glContext, NULL, &g_textureCache);
        if (err != kCVReturnSuccess) {
            NSLog(@"BeautyFilter: Failed to create texture cache: %d", err);
            [EAGLContext setCurrentContext:prevContext];
            [self beauty_capturer:capturer didCaptureVideoFrame:frame];
            return;
        }
    }

    // Reinitialize OpenGL resources if viewport resolution changes
    if (g_filterId == 0 || g_width != w || g_height != h) {
        if (g_filterId != 0) {
            release_beauty_filter(g_filterId);
            g_filterId = 0;
        }
        if (g_fbo != 0) {
            glDeleteFramebuffers(1, &g_fbo);
            g_fbo = 0;
        }

        g_width = w;
        g_height = h;
        g_filterId = init_beauty_filter(g_width, g_height);
        set_beauty_intensity(g_filterId, g_beauty_intensity);

        glGenFramebuffers(1, &g_fbo);
    }

    // Run Apple Vision Face Detection asynchronously in the background
    if (!g_isDetectingFace) {
        g_isDetectingFace = YES;
        CVPixelBufferRetain(srcPixelBuffer);

        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
            if (!g_visionHandler) {
                g_visionHandler = [[VNSequenceRequestHandler alloc] init];
                g_faceRequest = [[VNDetectFaceRectanglesRequest alloc] init];
            }

            NSError *error = nil;
            [g_visionHandler performRequests:@[g_faceRequest] onCVPixelBuffer:srcPixelBuffer error:&error];

            if (!error && g_faceRequest.results.count > 0) {
                VNFaceObservation *largestFace = nil;
                CGFloat largestArea = 0;
                for (VNFaceObservation *face in g_faceRequest.results) {
                    CGFloat area = face.boundingBox.size.width * face.boundingBox.size.height;
                    if (area > largestArea) {
                        largestArea = area;
                        largestFace = face;
                    }
                }

                if (largestFace) {
                    CGRect box = largestFace.boundingBox;
                    g_faceBounds = CGRectMake(box.origin.x, box.origin.y, box.origin.x + box.size.width, box.origin.y + box.size.height);
                }
            } else {
                g_faceBounds = CGRectMake(0, 0, 0, 0);
            }

            CVPixelBufferRelease(srcPixelBuffer);
            g_isDetectingFace = NO;
        });
    }

    set_face_bounds(g_filterId, g_faceBounds.origin.x, g_faceBounds.origin.y, 
                    g_faceBounds.origin.x + g_faceBounds.size.width, g_faceBounds.origin.y + g_faceBounds.size.height);
    set_beauty_intensity(g_filterId, g_beauty_intensity);

    // Bind source pixel buffer to an OpenGL texture
    CVOpenGLESTextureRef srcTextureRef = NULL;
    CVReturn err = CVOpenGLESTextureCacheCreateTextureFromImage(
        kCFAllocatorDefault, g_textureCache, srcPixelBuffer, NULL,
        GL_TEXTURE_2D, GL_RGBA, g_width, g_height, GL_BGRA, GL_UNSIGNED_BYTE, 0, &srcTextureRef
    );

    if (err != kCVReturnSuccess || !srcTextureRef) {
        NSLog(@"BeautyFilter: Failed to create src texture: %d", err);
        [EAGLContext setCurrentContext:prevContext];
        [self beauty_capturer:capturer didCaptureVideoFrame:frame];
        return;
    }

    GLuint srcTextureId = CVOpenGLESTextureGetName(srcTextureRef);

    // Allocate processed destination pixel buffer
    CVPixelBufferRef dstPixelBuffer = NULL;
    NSDictionary *pixelBufferAttributes = @{
        (id)kCVPixelBufferIOSurfacePropertiesKey: @{},
        (id)kCVPixelBufferOpenGLESCompatibilityKey: @YES
    };
    err = CVPixelBufferCreate(
        kCFAllocatorDefault, g_width, g_height, kCVPixelFormatType_32BGRA,
        (__bridge CFDictionaryRef)pixelBufferAttributes, &dstPixelBuffer
    );

    if (err != kCVReturnSuccess || !dstPixelBuffer) {
        NSLog(@"BeautyFilter: Failed to create dst pixel buffer: %d", err);
        CFRelease(srcTextureRef);
        [EAGLContext setCurrentContext:prevContext];
        [self beauty_capturer:capturer didCaptureVideoFrame:frame];
        return;
    }

    // Bind destination pixel buffer to target OpenGL texture
    CVOpenGLESTextureRef dstTextureRef = NULL;
    err = CVOpenGLESTextureCacheCreateTextureFromImage(
        kCFAllocatorDefault, g_textureCache, dstPixelBuffer, NULL,
        GL_TEXTURE_2D, GL_RGBA, g_width, g_height, GL_BGRA, GL_UNSIGNED_BYTE, 0, &dstTextureRef
    );

    if (err != kCVReturnSuccess || !dstTextureRef) {
        NSLog(@"BeautyFilter: Failed to create dst texture: %d", err);
        CFRelease(srcTextureRef);
        CVPixelBufferRelease(dstPixelBuffer);
        [EAGLContext setCurrentContext:prevContext];
        [self beauty_capturer:capturer didCaptureVideoFrame:frame];
        return;
    }

    GLuint dstTextureId = CVOpenGLESTextureGetName(dstTextureRef);

    // Bind FBO and process texture using the C++ beauty filter
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, dstTextureId, 0);

    process_texture(g_filterId, srcTextureId, g_width, g_height);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glFinish(); // Sync drawing commands to complete GPU pipeline before returning buffer

    CFRelease(srcTextureRef);
    CFRelease(dstTextureRef);
    CVOpenGLESTextureCacheFlush(g_textureCache, 0);

    [EAGLContext setCurrentContext:prevContext];

    // Forward the beautified buffer wrapped in a new RTCVideoFrame
    RTCCVPixelBuffer *processedBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:dstPixelBuffer];
    RTCVideoFrame *processedFrame = [[RTCVideoFrame alloc] initWithBuffer:processedBuffer
                                                                  rotation:frame.rotation
                                                               timestampNs:frame.timestampNs];

    // Delegate call to WebRTC pipeline
    [self beauty_capturer:capturer didCaptureVideoFrame:processedFrame];

    CVPixelBufferRelease(dstPixelBuffer);
}

@end
