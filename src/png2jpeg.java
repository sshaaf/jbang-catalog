/// usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.3

/** 
 * png2jpeg.java
 * Params: 
 *  dirPath - Currently it only accepts one directory
 *  quality - JPEG quality (0.0 = max compression, 1.0 = max quality). Default is 0.50.
 * 
 * Caution: it will save the jpg in the same path as the png files
 * 
 * I tend to take screenshots in PNG format, but I need them in JPEG format for content. Mac screenshots are usually big in size.
 * Converts PNG images to JPEG format with specified quality.
 * Tested with java 24.0.1 and jbang 0.89.0
 * OS: MacOS
 * author @sshaaf
*/


import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;
import java.util.logging.Logger;
import java.util.logging.Level;

import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.awt.Graphics2D;

@Command(name = "png2jpeg", mixinStandardHelpOptions = true, version = "png2jpeg 0.1",
        description = "convert screenshot PNG files to JPEG format")
class png2jpeg implements Callable<Integer> {

    private static final Logger logger = Logger.getLogger(png2jpeg.class.getName());

    @Parameters(index = "0", description = "The directory where the images reside", defaultValue = "World!")
    String dirPath;

    @Parameters(index = "1", description = "quality of the images, less quality will reduce size but also compromise quality", defaultValue = "World!")
    float quality = 0.50f; // default

    public static void main(String... args) {
        int exitCode = new CommandLine(new png2jpeg()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        logger.info("Initiating..");

        File inputDirectory = new File(dirPath);
        if (!inputDirectory.isDirectory()) {
            logger.log(Level.SEVERE, "Error: The provided path is not a valid directory: " + dirPath);
            return -1;
        }
            logger.info("Starting conversion in directory: " + inputDirectory.getAbsolutePath());
            logger.info("Using JPEG quality: " + quality);
            File[] files = inputDirectory.listFiles();
            if (files == null) {
                logger.log(Level.SEVERE, "Error: Could not list files in the directory. Check permissions.");
                return -1;
            }
            int convertedCount = 0;
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase().endsWith(".png")) {
                    logger.info("Processing: " + file.getName());
                    try {
                        boolean success = convertPngToJpeg(file, quality);
                        if (success) {
                            convertedCount++;
                            logger.info(" -> Converted successfully.");
                        } else {
                            logger.info(" -> Conversion failed (see previous errors).");
                        }
                    } catch (IOException e) {
                        logger.log(Level.SEVERE, "Error processing file " + file.getName() + ": " + e.getMessage());

                    }
                }
            }
        logger.info("\nConversion finished. " + convertedCount + " PNG files converted to JPEG.");

        return 0;
    }


    private static boolean convertPngToJpeg(File pngFile, float quality) throws IOException {

        BufferedImage pngImage = ImageIO.read(pngFile);
        if (pngImage == null) {
            logger.log(Level.SEVERE, " Could not read image file (may not be a valid PNG): " + pngFile.getName());
            return false;
        }

        BufferedImage rgbImage = new BufferedImage(
                pngImage.getWidth(),
                pngImage.getHeight(),
                BufferedImage.TYPE_INT_RGB // Force to standard RGB
        );

        Graphics2D g2d = rgbImage.createGraphics();
        g2d.setColor(java.awt.Color.WHITE);
        g2d.fillRect(0, 0, rgbImage.getWidth(), rgbImage.getHeight());
        g2d.drawImage(pngImage, 0, 0, null);
        g2d.dispose();

        String outputFilename = pngFile.getName().substring(0, pngFile.getName().lastIndexOf('.')) + ".jpg";
        File jpegFile = new File(pngFile.getParent(), outputFilename);

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        ImageWriter writer = writers.next();

        ImageWriteParam jpegParams = writer.getDefaultWriteParam();
        jpegParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        jpegParams.setCompressionQuality(quality);

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(new FileOutputStream(jpegFile))) {
            writer.setOutput(ios);
            IIOImage iioImage = new IIOImage(rgbImage, null, null);
            writer.write(null, iioImage, jpegParams);
        } finally {
            writer.dispose();
        }

        return true;
    }

}
