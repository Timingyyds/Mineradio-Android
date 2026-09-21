package com.mineradio.app.wallpaper

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * OpenGL 着色器编译工具
 * 移植自 project_lw 的 Utils.kt
 */
internal object ShaderUtils {
    private const val TAG = "ShaderUtils"

    /**
     * 编译 GLES3.0 着色器
     */
    fun compileShaderGLES30(
        context: Context,
        shaderType: Int,
        shaderRes: Int,
    ): Int {
        val source = readRawResource(context, shaderRes) ?: return 0
        val shader = GLES30.glCreateShader(shaderType)
        if (shader == 0) {
            throw RuntimeException("Failed to create shader")
        }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException(log)
        }
        return shader
    }

    /**
     * 链接 GLES3.0 程序
     */
    fun linkProgramGLES30(
        vertShader: Int,
        fragShader: Int,
    ): Int {
        val program = GLES30.glCreateProgram()
        if (program == 0) {
            throw RuntimeException("Failed to create program")
        }
        GLES30.glAttachShader(program, vertShader)
        GLES30.glAttachShader(program, fragShader)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException(log)
        }
        return program
    }

    /**
     * 编译 GLES2.0 着色器
     */
    fun compileShaderGLES20(
        context: Context,
        shaderType: Int,
        shaderRes: Int,
    ): Int {
        val source = readRawResource(context, shaderRes) ?: return 0
        val shader = GLES20.glCreateShader(shaderType)
        if (shader == 0) {
            throw RuntimeException("Failed to create shader")
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException(log)
        }
        return shader
    }

    /**
     * 链接 GLES2.0 程序
     */
    fun linkProgramGLES20(
        vertShader: Int,
        fragShader: Int,
    ): Int {
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            throw RuntimeException("Failed to create program")
        }
        GLES20.glAttachShader(program, vertShader)
        GLES20.glAttachShader(program, fragShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException(log)
        }
        return program
    }

    /**
     * 读取 raw 资源为字符串
     */
    private fun readRawResource(
        context: Context,
        resId: Int,
    ): String? =
        try {
            val inputStream = context.resources.openRawResource(resId)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val builder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                builder.append(line)
                builder.append('\n')
            }
            reader.close()
            builder.toString()
        } catch (e: IOException) {
            Log.e(TAG, "readRawResource: ", e)
            null
        }
}
