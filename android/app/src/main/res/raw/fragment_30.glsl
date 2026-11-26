#version 300 es
#ifdef GL_OES_EGL_image_external_essl3
#extension GL_OES_EGL_image_external_essl3 : require
#else
#extension GL_OES_EGL_image_external : require
#endif

precision mediump float;

uniform samplerExternalOES frame;

in vec2 tex_coord;
out vec4 frag_color;

void main() {
    frag_color = texture(frame, tex_coord);
}
