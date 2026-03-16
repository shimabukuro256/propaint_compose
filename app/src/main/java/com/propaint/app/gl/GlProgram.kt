package com.propaint.app.gl

import android.opengl.GLES20
import android.util.Log

/** GLSL シェーダーのコンパイル・リンクをラップするユーティリティ。 */
internal class GlProgram(vertSrc: String, fragSrc: String) {

    val id: Int = run {
        val vert = compile(GLES20.GL_VERTEX_SHADER, vertSrc)
        val frag = compile(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vert)
            GLES20.glAttachShader(prog, frag)
            GLES20.glLinkProgram(prog)
            val status = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) Log.e("GlProgram", "Link error: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteShader(vert)
            GLES20.glDeleteShader(frag)
        }
    }

    fun use() = GLES20.glUseProgram(id)
    fun attrib(name: String) = GLES20.glGetAttribLocation(id, name)
    fun uniform(name: String) = GLES20.glGetUniformLocation(id, name)
    fun delete() = GLES20.glDeleteProgram(id)

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e("GlProgram", "Compile error: ${GLES20.glGetShaderInfoLog(shader)}")
        return shader
    }
}
