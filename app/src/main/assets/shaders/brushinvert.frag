
uniform sampler2D g_Texture0;

varying vec3 g_ScreenPosition;

void main() {
	
	vec2 texCoords = g_ScreenPosition.xy / g_ScreenPosition.z;
	texCoords = texCoords * vec2(0.5, -0.5) + 0.5;
	vec4 _sample = texSample2D(g_Texture0, texCoords);

	float lightness = dot(_sample.rgb, vec3(0.3, 0.59, 0.11));
	float color = step(lightness, 0.5);

	gl_FragColor = vec4(vec3(1.0, 1.0, 1.0) * color, 1.0);
}
