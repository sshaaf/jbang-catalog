///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.apache.pdfbox:pdfbox:3.0.1
//DEPS org.apache.poi:poi:5.2.5
//DEPS org.apache.poi:poi-ooxml:5.2.5
//DEPS org.apache.poi:poi-scratchpad:5.2.5

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;

/**
 * JBang script to convert PowerPoint (PPTX) files to high-quality PNG images
 *
 * Usage: jbang pptx2png.java <pptx-file> [output-prefix] [dpi]
 *
 * Example: jbang pptx2png.java presentation.pptx slide 300
 */
public class pptx2png {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: jbang pptx2png.java <pptx-file> [output-prefix] [dpi]");
            System.err.println("Example: jbang pptx2png.java presentation.pptx mca 300");
            System.exit(1);
        }

        String pptxFile = args[0];
        String outputPrefix = args.length > 1 ? args[1] : "slide";
        int dpi = args.length > 2 ? Integer.parseInt(args[2]) : 300;

        File inputFile = new File(pptxFile);
        if (!inputFile.exists()) {
            System.err.println("Error: File not found: " + pptxFile);
            System.exit(1);
        }

        System.out.println("Converting " + pptxFile + " to PNG images...");
        System.out.println("Output prefix: " + outputPrefix);
        System.out.println("DPI: " + dpi);
        System.out.println();

        convertPptxToPng(inputFile, outputPrefix, dpi);
    }

    private static void convertPptxToPng(File pptxFile, String outputPrefix, int dpi) throws Exception {
        // Read the PowerPoint file
        try (FileInputStream fis = new FileInputStream(pptxFile);
             XMLSlideShow ppt = new XMLSlideShow(fis)) {

            List<XSLFSlide> slides = ppt.getSlides();
            System.out.println("Found " + slides.size() + " slides");
            System.out.println();

            // Get the presentation dimensions
            Dimension pgsize = ppt.getPageSize();
            double scale = dpi / 72.0; // 72 DPI is the default
            int width = (int) (pgsize.width * scale);
            int height = (int) (pgsize.height * scale);

            System.out.println("Output dimensions: " + width + "x" + height + " pixels");
            System.out.println();

            // Convert each slide to PNG
            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);

                // Create a buffered image with high quality
                BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = img.createGraphics();

                // Enable high-quality rendering
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                // Fill with white background
                graphics.setPaint(Color.WHITE);
                graphics.fill(new Rectangle2D.Float(0, 0, width, height));

                // Scale the graphics context
                graphics.scale(scale, scale);

                // Draw the slide
                slide.draw(graphics);
                graphics.dispose();

                // Save as PNG
                String outputFile = String.format("%s-%02d.png", outputPrefix, i + 1);
                ImageIO.write(img, "PNG", new File(outputFile));
                System.out.println("Saved: " + outputFile);
            }

            System.out.println();
            System.out.println("Successfully converted " + slides.size() + " slides to PNG images!");
        }
    }
}
