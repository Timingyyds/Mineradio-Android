#version 100
#extension GL_OES_EGL_image_external : require

precision mediump float;

uniform samplerExternalOES frame;

varying vec2 tex_coord;

void main() {
    gl_FragColor = texture2D(frame, tex_coord);
}
