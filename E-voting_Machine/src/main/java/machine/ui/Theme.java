package machine.ui;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The terminal's visual language: the Indian national flag palette, applied restrainedly.
 *
 * <p>Saffron and green carry accents and state, white and a warm paper tone carry content,
 * and the navy of the Ashoka Chakra carries chrome and primary actions. The Chakra itself —
 * the 24-spoke wheel from the flag's centre — appears as a drawn emblem rather than a
 * borrowed icon, and the vote-cast screen uses its own signature: a stylised indelible-ink
 * mark, the one physical, unmistakable artefact of having voted in India. Large type and
 * strong contrast throughout, because the quotation specifies a 7" display and physical
 * buttons chosen for elderly and differently-abled voters — every colour cue is paired with
 * a text label rather than standing alone, and nothing below depends on a pointer.
 */
public final class Theme {

    // ── Flag palette ────────────────────────────────────────────────────────
    public static final Color SAFFRON          = new Color(0xFF, 0x99, 0x33);
    public static final Color SAFFRON_DARK     = new Color(0x9C, 0x46, 0x00);
    public static final Color WHITE            = new Color(0xFF, 0xFF, 0xFF);
    public static final Color GREEN            = new Color(0x0F, 0x7A, 0x1E);
    public static final Color GREEN_DARK       = new Color(0x0A, 0x57, 0x16);
    public static final Color CHAKRA_NAVY      = new Color(0x0B, 0x1F, 0x4B);
    public static final Color CHAKRA_NAVY_DEEP = new Color(0x06, 0x12, 0x30);

    /** The colour of election indelible ink — deliberately not on the flag palette, so the
     *  one screen that uses it (vote cast) reads as a distinct, ceremonial moment. */
    public static final Color INK_MARK         = new Color(0x47, 0x1B, 0x4C);

    // ── Neutrals ────────────────────────────────────────────────────────────
    public static final Color CANVAS           = new Color(0xF6, 0xF3, 0xEC);
    public static final Color INK              = new Color(0x17, 0x18, 0x1D);
    public static final Color INK_MUTED        = new Color(0x5B, 0x5F, 0x6B);
    public static final Color HAIRLINE         = new Color(0xE3, 0xDF, 0xD2);
    public static final Color ALERT            = new Color(0xB3, 0x1C, 0x1C);
    private static final Color SHADOW          = new Color(0x0B, 0x1F, 0x4B, 28);

    // ── Type scale ──────────────────────────────────────────────────────────
    private static final String FAMILY = pickFontFamily();

    public static final Font DISPLAY   = new Font(FAMILY, Font.BOLD,  46);
    public static final Font HEADING   = new Font(FAMILY, Font.BOLD,  30);
    public static final Font SUBHEAD   = new Font(FAMILY, Font.BOLD,  22);
    public static final Font BODY      = new Font(FAMILY, Font.PLAIN, 19);
    public static final Font BODY_BOLD = new Font(FAMILY, Font.BOLD,  19);
    public static final Font CAPTION   = new Font(FAMILY, Font.PLAIN, 15);
    public static final Font EYEBROW   = new Font(FAMILY, Font.BOLD,  13);
    public static final Font MONO      = new Font(Font.MONOSPACED,    Font.PLAIN, 16);

    private Theme() {}

    /** Prefers a font with good Devanagari and Tamil coverage, since ballots are bilingual. */
    private static String pickFontFamily() {
        String[] preferred = { "Nirmala UI", "Segoe UI", "Noto Sans", "DejaVu Sans", "Dialog" };
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        for (String candidate : preferred) {
            for (String family : available) {
                if (family.equalsIgnoreCase(candidate)) {
                    return family;
                }
            }
        }
        return Font.SANS_SERIF;
    }

    // ── Building blocks ─────────────────────────────────────────────────────

    public static JLabel label(String text, Font font, Color colour) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(colour);
        return label;
    }

    public static JLabel centred(String text, Font font, Color colour) {
        JLabel label = label(text, font, colour);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    /** A small bold label with letterspacing faked via thin spaces, for section eyebrows. */
    public static JLabel eyebrow(String text, Color colour) {
        StringBuilder spaced = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            spaced.append(text.charAt(i));
            if (i < text.length() - 1) {
                spaced.append('\u2009');
            }
        }
        return centred(spaced.toString().toUpperCase(), EYEBROW, colour);
    }

    public static JPanel column() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    public static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    public static Component gap(int height) {
        return Box.createRigidArea(new Dimension(1, height));
    }

    public static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    // ── Chrome ──────────────────────────────────────────────────────────────

    /**
     * The header's background: a deep navy vertical gradient rather than a flat fill, so the
     * chrome reads as a considered surface rather than a default panel colour.
     */
    public static JComponent navyGradient() {
        return new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, CHAKRA_NAVY, 0, getHeight(), CHAKRA_NAVY_DEEP));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
    }

    /**
     * The thin tricolour accent used under the header and above the footer.
     *
     * <p>Drawn as a slim line rather than a fat block: it reads as a considered accent, the
     * way a ribbon or a document seal uses colour, rather than a literal flag graphic.
     */
    public static JComponent tricolourAccent(int thickness) {
        JComponent band = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                int width = getWidth();
                int third = Math.max(1, width / 3);
                g.setColor(SAFFRON);
                g.fillRect(0, 0, third, getHeight());
                g.setColor(WHITE);
                g.fillRect(third, 0, third, getHeight());
                g.setColor(GREEN);
                g.fillRect(third * 2, 0, width - third * 2, getHeight());
            }
        };
        band.setPreferredSize(new Dimension(10, thickness));
        band.setMaximumSize(new Dimension(Integer.MAX_VALUE, thickness));
        return band;
    }

    /** A small filled circle used to carry status colour without relying on colour alone —
     *  it always sits beside a text label. */
    public static JComponent statusDot(Color colour) {
        JComponent dot = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(colour);
                g2.fill(new Ellipse2D.Double(0, 0, getWidth(), getHeight()));
                g2.dispose();
            }
        };
        dot.setPreferredSize(new Dimension(10, 10));
        dot.setOpaque(false);
        return dot;
    }

    // ── Surfaces ────────────────────────────────────────────────────────────

    /** A rounded card, lifted off the canvas with a soft drop shadow. */
    public static JPanel card() {
        return roundedSurface(WHITE, 22, true, 0);
    }

    /** A rounded card with a faint Chakra watermark centred behind its content — used for
     *  the two screens that bookend a voter's visit, idle and success, so the emblem reads
     *  as a quiet signature rather than a logo stamped on every screen. */
    public static JPanel cardWithWatermark(int watermarkDiameter) {
        return roundedSurface(WHITE, 22, true, watermarkDiameter);
    }

    /** A rounded card without the shadow, for nesting inside another surface. */
    public static JPanel flatSurface(Color background, int radius) {
        return roundedSurface(background, radius, false, 0);
    }

    private static JPanel roundedSurface(Color background, int radius, boolean shadow, int watermarkDiameter) {
        int shadowSpace = shadow ? 7 : 0;
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                RoundRectangle2D.Double shape =
                        new RoundRectangle2D.Double(0, 0, w - shadowSpace, h - shadowSpace, radius, radius);
                if (shadow) {
                    g2.setColor(SHADOW);
                    g2.fill(new RoundRectangle2D.Double(3, shadowSpace, w - 6, h - shadowSpace - 3, radius, radius));
                }
                g2.setColor(background);
                g2.fill(shape);
                if (watermarkDiameter > 0) {
                    Shape previousClip = g2.getClip();
                    g2.clip(shape);
                    paintChakra(g2, (w - shadowSpace) / 2, (h - shadowSpace) / 2,
                            watermarkDiameter / 2, new Color(0x0B, 0x1F, 0x4B, 14), 2.2f);
                    g2.setClip(previousClip);
                }
                g2.setColor(HAIRLINE);
                g2.setStroke(new BasicStroke(1.2f));
                g2.draw(shape);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(padding(28, 32, 28 + shadowSpace, 32 + shadowSpace));
        return panel;
    }

    // ── The Ashoka Chakra ───────────────────────────────────────────────────

    /**
     * Draws the 24-spoke wheel from the flag's centre. Used both as a crisp small emblem in
     * the header and, at very low opacity and large scale, as a watermark behind the idle
     * and success cards — a quiet signature rather than a literal logo.
     */
    public static void paintChakra(Graphics2D g2, int centreX, int centreY, int radius, Color colour, float strokeWidth) {
        Graphics2D wheel = (Graphics2D) g2.create();
        wheel.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        wheel.setColor(colour);
        wheel.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        wheel.draw(new Ellipse2D.Double(centreX - radius, centreY - radius, radius * 2.0, radius * 2.0));

        double hubRadius = radius * 0.11;
        wheel.fill(new Ellipse2D.Double(centreX - hubRadius, centreY - hubRadius, hubRadius * 2, hubRadius * 2));

        double innerR = radius * 0.17;
        double outerR = radius * 0.90;
        for (int spoke = 0; spoke < 24; spoke++) {
            double angle = Math.toRadians(spoke * 15.0);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            wheel.draw(new Line2D.Double(
                    centreX + innerR * cos, centreY + innerR * sin,
                    centreX + outerR * cos, centreY + outerR * sin));
        }
        wheel.dispose();
    }

    /** A ready-to-place emblem, for the header and small inline uses. */
    public static JComponent chakraEmblem(int size, Color colour) {
        JComponent emblem = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                paintChakra((Graphics2D) g, getWidth() / 2, getHeight() / 2,
                        getWidth() / 2 - 2, colour, Math.max(1.5f, size / 22f));
            }
        };
        emblem.setPreferredSize(new Dimension(size, size));
        emblem.setOpaque(false);
        return emblem;
    }

    // ── The indelible ink mark ──────────────────────────────────────────────

    /**
     * The signature of the vote-cast screen: a simplified fingertip with the indelible-ink
     * cap applied to the nail, the way an Election Commission officer marks a voter's left
     * index finger after they vote. This is the one moment the ink colour appears at all —
     * everywhere else the palette stays on the flag.
     */
    public static JComponent inkMarkGlyph(int width, int height) {
        JComponent glyph = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                int radius = w / 2;
                RoundRectangle2D.Double finger = new RoundRectangle2D.Double(
                        w * 0.5 - radius * 0.62, 0, radius * 1.24, h - 4, radius, radius);

                g2.setColor(WHITE);
                g2.fill(finger);

                Shape previousClip = g2.getClip();
                g2.clip(finger);
                g2.setColor(INK_MARK);
                g2.fill(new RoundRectangle2D.Double(
                        w * 0.5 - radius * 0.62, 0, radius * 1.24, h * 0.46, radius, radius));
                g2.setClip(previousClip);

                g2.setColor(CHAKRA_NAVY);
                g2.setStroke(new BasicStroke(2.4f));
                g2.draw(finger);

                g2.setColor(new Color(INK_MARK.getRed(), INK_MARK.getGreen(), INK_MARK.getBlue(), 130));
                g2.fill(new Ellipse2D.Double(w * 0.78, h * 0.30, w * 0.09, w * 0.09));
                g2.fill(new Ellipse2D.Double(w * 0.08, h * 0.52, w * 0.06, w * 0.06));
                g2.dispose();
            }
        };
        glyph.setPreferredSize(new Dimension(width, height));
        glyph.setOpaque(false);
        return glyph;
    }

    // ── Buttons ─────────────────────────────────────────────────────────────

    /** A filled, fully-rounded button with a hover/press state, painted rather than relying
     *  on the platform look and feel, so it stays consistent across the Pi and a laptop demo. */
    public static JButton pillButton(String text, Color base, Color foreground) {
        return new PillButton(text, base, foreground, false);
    }

    /** A pill button with a hairline outline and no fill, for secondary actions. */
    public static JButton outlineButton(String text, Color accent) {
        return new PillButton(text, accent, accent, true);
    }

    private static final class PillButton extends JButton {
        private final Color base;
        private final boolean outline;
        private boolean hover;
        private boolean pressed;

        PillButton(String text, Color base, Color foreground, boolean outline) {
            super(text);
            this.base = base;
            this.outline = outline;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setForeground(outline ? base : foreground);
            setFont(BODY_BOLD);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(padding(13, 26, 13, 26));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { pressed = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int arc = Math.min(h, w);
            RoundRectangle2D.Double shape = new RoundRectangle2D.Double(0, 0, w - 1, h - 1, arc, arc);

            if (outline) {
                // No fill at rest, so this reads correctly against any surface behind it —
                // including the officer panel's dark card, where a solid fill would hide
                // white-on-white text. Only a faint hover tint is drawn.
                if (hover) {
                    g2.setColor(tint(base, 0.85f));
                    g2.fill(shape);
                }
                g2.setStroke(new BasicStroke(1.6f));
                g2.setColor(base);
                g2.draw(shape);
            } else {
                Color top = pressed ? base.darker() : (hover ? lighten(base, 0.10f) : base);
                Color bottom = pressed ? base.darker().darker() : base.darker();
                g2.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
                g2.fill(shape);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static Color lighten(Color c, float amount) {
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        hsb[2] = Math.min(1f, hsb[2] + amount);
        return Color.getHSBColor(hsb[0], hsb[1], hsb[2]);
    }

    /** A near-white wash of a colour, for a subtle hover fill behind outline buttons/rows. */
    private static Color tint(Color c, float towardsWhite) {
        int r = (int) (c.getRed()   + (255 - c.getRed())   * towardsWhite);
        int g = (int) (c.getGreen() + (255 - c.getGreen()) * towardsWhite);
        int b = (int) (c.getBlue()  + (255 - c.getBlue())  * towardsWhite);
        return new Color(r, g, b);
    }

    /**
     * A key-cap chip showing which physical button performs an action.
     *
     * <p>The display is non-touch, so every action on screen has to name its button. These
     * appear beside each candidate and each prompt.
     */
    public static JComponent keyCap(String key, Color background, Color foreground) {
        JComponent cap = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(background);
                g2.fill(new RoundRectangle2D.Double(0, 0, w - 1, h - 1, 14, 14));
                g2.setColor(background.darker());
                g2.setStroke(new BasicStroke(1.4f));
                g2.draw(new RoundRectangle2D.Double(0, 0, w - 1, h - 1, 14, 14));
                g2.setFont(new Font(FAMILY, Font.BOLD, 20));
                g2.setColor(foreground);
                FontMetrics metrics = g2.getFontMetrics();
                int textX = (w - metrics.stringWidth(key)) / 2;
                int textY = (h - metrics.getHeight()) / 2 + metrics.getAscent();
                g2.drawString(key, textX, textY);
                g2.dispose();
            }
        };
        cap.setPreferredSize(new Dimension(52, 44));
        cap.setMinimumSize(new Dimension(52, 44));
        cap.setMaximumSize(new Dimension(52, 44));
        cap.setOpaque(false);
        return cap;
    }

    /**
     * A gentle pulse — two concentric rings expanding and fading — for the fingerprint pad
     * while it waits for a scan. Runs only while visible: {@link #start()}/{@link #stop()}
     * are called by the frame as the fingerprint screen is entered and left, so the timer
     * never ticks on screens where nobody can see it.
     */
    public static final class PulsingRings extends JComponent {
        private final javax.swing.Timer timer;
        private float phase;

        public PulsingRings(int diameter) {
            setPreferredSize(new Dimension(diameter, diameter));
            setMaximumSize(new Dimension(diameter, diameter));
            setOpaque(false);
            timer = new javax.swing.Timer(40, e -> {
                phase += 0.02f;
                if (phase > 1f) {
                    phase = 0f;
                }
                repaint();
            });
        }

        public void start() {
            phase = 0f;
            if (!timer.isRunning()) {
                timer.start();
            }
        }

        public void stop() {
            timer.stop();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int cx = w / 2;
            int cy = h / 2;
            int baseRadius = Math.min(w, h) / 2 - 6;

            for (int ring = 0; ring < 2; ring++) {
                float ringPhase = (phase + ring * 0.5f) % 1f;
                float radius = baseRadius * (0.55f + ringPhase * 0.45f);
                int alpha = Math.max(0, (int) (110 * (1f - ringPhase)));
                g2.setColor(new Color(CHAKRA_NAVY.getRed(), CHAKRA_NAVY.getGreen(), CHAKRA_NAVY.getBlue(), alpha));
                g2.setStroke(new BasicStroke(2.4f));
                g2.draw(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
            }

            g2.setColor(WHITE);
            g2.fill(new Ellipse2D.Double(cx - baseRadius * 0.5, cy - baseRadius * 0.5, baseRadius, baseRadius));
            g2.setColor(CHAKRA_NAVY);
            g2.setStroke(new BasicStroke(3f));
            g2.draw(new Ellipse2D.Double(cx - baseRadius * 0.5, cy - baseRadius * 0.5, baseRadius, baseRadius));
            g2.dispose();
        }

        /** Plain JComponent has no AccessibleContext by default; the fingerprint screen
         *  names this pad for assistive tech, so it needs one. */
        @Override
        public javax.accessibility.AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleJComponent() {
                    @Override
                    public javax.accessibility.AccessibleRole getAccessibleRole() {
                        return javax.accessibility.AccessibleRole.PUSH_BUTTON;
                    }
                };
            }
            return accessibleContext;
        }
    }

    /**
     * Applies Swing defaults once at startup.
     *
     * <p>Keeps tooltips and dialogs consistent with the kiosk rather than inheriting the
     * host desktop's look, which on a Pi running a bare window manager is unpredictable.
     */
    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            // The default look and feel is acceptable; appearance is not worth failing over.
        }
        UIManager.put("OptionPane.messageFont", BODY);
        UIManager.put("OptionPane.buttonFont", BODY_BOLD);
        UIManager.put("Label.font", BODY);
        UIManager.put("Panel.background", CANVAS);
    }
}
