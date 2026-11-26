#version 100

attribute vec2 in_position;
attribute vec2 in_tex_coord;

uniform mat4 mvp;

varying vec2 tex_coord;

void main() {
    gl_Position = mvp * vec4(in_position, 1.0, 1.0);
    tex_coord = in_tex_coord;
}
