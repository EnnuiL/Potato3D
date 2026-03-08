package eu.pb4.softwaregl.blaze3d.texture;

public record RGBATexture(int[] data, int width, int height) implements TextureLike {
    public RGBATexture(int width, int height) {
        this(new int[width * height], width, height);
    }

    public int get(int x, int y) {
        return this.data[x + y * width];
    }

    public void set(int x, int y, int color) {
        this.data[x + y * width] = color;
    }
}
