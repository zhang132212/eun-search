import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;

public class GenPreview {
    public static void main(String[] args) throws Exception {
        int W = 640, CX = 20, CW = 600, HH = 76, RH = 66, FH = 38, TM = 24, BM = 20, PD = 20, IS = 40;
        int TH = TM + HH + RH + FH + BM;
        BufferedImage img = new BufferedImage(W, TH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, TH);

        g.setColor(new Color(0, 0, 0, 15));
        g.fill(new RoundRectangle2D.Float(CX + 2, TM + 2, CW, TH - TM - BM, 14, 14));

        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(CX, TM, CW, TH - TM - BM, 14, 14));
        g.setColor(new Color(0xD4D4D8));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(CX, TM, CW, TH - TM - BM, 14, 14));

        g.setColor(new Color(0x18181B));
        g.fill(new RoundRectangle2D.Float(CX, TM, CW, HH, 14, 14));
        g.fillRect(CX, TM + HH / 2, CW, HH / 2);

        g.setColor(new Color(0x22C55E));
        int d = 12;
        g.fillOval(CX + CW / 2 - 100, TM + HH / 2 - d / 2, d, d);
        g.fillOval(CX + CW / 2 + 88,  TM + HH / 2 - d / 2, d, d);

        Font font = new Font("Microsoft YaHei", Font.BOLD, 28);
        g.setFont(font);
        g.setColor(Color.WHITE);
        String title = "物品容量";
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(title);
        g.drawString(title, CX + (CW - tw) / 2, TM + (HH + fm.getAscent() - fm.getDescent()) / 2);

        int iy = TM + HH + (RH - IS) / 2;
        int ix = CX + PD;

        BufferedImage icon = ImageIO.read(new File("preview/carrot_out.png"));
        g.drawImage(icon, ix, iy, IS, IS, null);

        int tx = ix + IS + 12;
        int tW = CW - PD * 2 - IS - 12;

        g.setColor(new Color(0x18181B));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        g.drawString("胡萝卜", tx, iy + 20);

        g.setColor(new Color(0x71717A));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        g.drawString("3,840个 (2盒+384) · 占用60格", tx + 60, iy + 20);

        int bY = iy + 34, bH = 16;
        g.setColor(new Color(0xF4F4F5));
        g.fillRoundRect(tx, bY, tW, bH, 8, 8);
        g.setColor(new Color(0xEAB308));
        int fW = (int)(tW * 0.0185);
        g.fillRoundRect(tx, bY, fW, bH, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
        String pct = "1.85%";
        fm = g.getFontMetrics();
        int pw = fm.stringWidth(pct);
        g.drawString(pct, tx + (tW - pw) / 2, bY + bH - 4);

        g.setColor(new Color(0xF4F4F5));
        g.drawLine(CX + PD, iy + RH, CX + CW - PD, iy + RH);

        int fY = TM + HH + RH;
        g.drawLine(CX + PD, fY, CX + CW - PD, fY);
        g.setColor(new Color(0xA1A1AA));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        fm = g.getFontMetrics();
        String fl = "EunSearch · 扫描 #胡萝卜";
        g.drawString(fl, CX + PD, fY + FH / 2 + fm.getAscent() / 2 - 2);
        String fr = "120容器 · 3,240格 · 耗时1.2s";
        g.drawString(fr, CX + CW - PD - fm.stringWidth(fr), fY + FH / 2 + fm.getAscent() / 2 - 2);

        g.dispose();
        ImageIO.write(img, "PNG", new File("preview/final_preview.png"));
        System.out.println("Done: preview/final_preview.png");
    }
}
