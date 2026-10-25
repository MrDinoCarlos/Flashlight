#version 150

#define BIG 1000000
#define FIXEDPOINT 1000.0
#define DSCALE 10.0

#define NEAR 0.05
#define FAR 1024.0

#define LIGHTR 18.0
#define MIN_LIGHTR 3.0
#define RADIUS_CURVE 0.65
#define FALLOFF_POWER 1.35
#define LIGHT_BOOST 1.45

#define ALPHACUTOFF (21.5 / 255.0)
#define LIGHTALPHA (5.0 / 255.0)
#define LIGHTALPHATOLERANCE (0.5 / 255.0)
#define LIGHTDEPTH 0.025

float decodeExpansionScale(int code) {
    return float(clamp(code, 0, 15) + 1) * 0.25;
}

// v5 carries this value in four carrier alpha nibbles. Keeping the actual
// projection avoids the visible position/radius jumps of the old 4-bit FOV.
int encodeExactProjectionK(float value) {
    float normalized = log2(clamp(value, 0.0000152587890625, 256.0)
        / 0.0000152587890625) / 24.0;
    return int(floor(normalized * 65535.0 + 0.5));
}

float decodeExactProjectionK(int code) {
    return 0.0000152587890625 * exp2(24.0 * float(clamp(code, 0, 65535)) / 65535.0);
}

// The Fabulous post chain does not expose the camera projection matrix. Carry
// its vertical conversion in the OptiFine-safe 24-bit color marker.
int encodeProjectionK(float value) {
    if (value < 0.141421) return 0;
    if (value < 0.178885) return 1;
    if (value < 0.223607) return 2;
    if (value < 0.280625) return 3;
    if (value < 0.354965) return 4;
    if (value < 0.447214) return 5;
    if (value < 0.561249) return 6;
    if (value < 0.709930) return 7;
    if (value < 0.894427) return 8;
    if (value < 1.095445) return 9;
    if (value < 1.296148) return 10;
    if (value < 1.496663) return 11;
    if (value < 1.788854) return 12;
    if (value < 2.190890) return 13;
    if (value < 2.683282) return 14;
    return 15;
}

float decodeProjectionK(int code) {
    if (code <= 0) return 0.125;
    if (code == 1) return 0.160;
    if (code == 2) return 0.200;
    if (code == 3) return 0.250;
    if (code == 4) return 0.315;
    if (code == 5) return 0.400;
    if (code == 6) return 0.500;
    if (code == 7) return 0.630;
    if (code == 8) return 0.800;
    if (code == 9) return 1.000;
    if (code == 10) return 1.200;
    if (code == 11) return 1.400;
    if (code == 12) return 1.600;
    if (code == 13) return 2.000;
    if (code == 14) return 2.400;
    return 3.000;
}

bool isGUI(mat4 ProjMat) { 
    return abs(ProjMat[2][3]) <= 1.0 / BIG;
}

bool isHand(float fogs, float foge) { // also includes panorama
    return fogs >= foge;
}

float LinearizeDepth(float depth) {
    float z = depth * 2.0 - 1.0;
    return 2.0 * (NEAR * FAR) / (FAR + NEAR - z * (FAR - NEAR));    
}

float luminance(vec3 rgb) {
    return 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b;
}
