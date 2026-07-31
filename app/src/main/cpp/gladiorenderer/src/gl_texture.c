#include "gl_texture.h"
#include "gl_context.h"

static GLuint maxTextureId = 1;
static GLTexture nullTexture = {0};

static GLTexture* createNamedTexture(GLuint id) {
    GLTexture* texture = calloc(1, sizeof(GLTexture));
    texture->type = GL_TEXTURE_2D;
    glGenTextures(1, &texture->id);
    SparseArray_put(currentRenderer->clientState.textures, id, texture);
    return texture;
}

GLuint GLTexture_create() {
    GLX_CONTEXT_LOCK();
    GLuint id = maxTextureId++;
    createNamedTexture(id);
    GLX_CONTEXT_UNLOCK();
    return id;
}

GLTexture* GLTexture_getBound(GLenum target) {
    uint8_t activeTexture = currentRenderer->clientState.activeTexture;
    return currentRenderer->clientState.texture[activeTexture][indexOfGLTarget(target)];
}

void GLTexture_bind(GLenum target, GLuint id) {
    uint8_t activeTexture = currentRenderer->clientState.activeTexture;
    if (id == 0) {
        currentRenderer->clientState.texture[activeTexture][indexOfGLTarget(target)] = NULL;
        glBindTexture(target, 0);
        return;
    }
    GLX_CONTEXT_LOCK();
    GLTexture* texture = SparseArray_get(currentRenderer->clientState.textures, id);
    if (!texture) texture = createNamedTexture(id);
    texture->type = target;
    GLX_CONTEXT_UNLOCK();
    currentRenderer->clientState.texture[activeTexture][indexOfGLTarget(target)] = texture;
    glBindTexture(target, texture->id);
}

void GLTexture_setActiveUnit(GLenum unit) {
    currentRenderer->clientState.activeTexture = MIN(unit - GL_TEXTURE0, MAX_TEXTURES-1);
    glActiveTexture(unit);
}

GLTexture* GLTexture_get(GLuint id) {
    GLX_CONTEXT_LOCK();
    GLTexture* texture = SparseArray_get(currentRenderer->clientState.textures, id);
    GLX_CONTEXT_UNLOCK();
    return texture ? texture : &nullTexture;
}

void GLTexture_delete(GLuint id) {
    GLX_CONTEXT_LOCK();
    GLTexture* texture = SparseArray_get(currentRenderer->clientState.textures, id);
    if (texture) {
        for (int i = 0, j; i < MAX_TEXTURES; i++) {
            for (j = 0; j < MAX_TEXTURE_TARGETS; j++) {
                if (texture == currentRenderer->clientState.texture[i][j]) currentRenderer->clientState.texture[i][j] = NULL;
            }
        }

        glDeleteTextures(1, &texture->id);
        SparseArray_remove(currentRenderer->clientState.textures, id);
        free(texture);
    }
    GLX_CONTEXT_UNLOCK();
}