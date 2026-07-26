package br.com.jorgemelo.nimbusfilemanager.conversion.application;

/**
 * How the conversion reports where it is. A batch has two progress dimensions
 * and the screen shows both: how many files are done, and how far into the file
 * being encoded right now ffmpeg has got - which is what keeps a single
 * multi-minute video from looking frozen.
 */
@FunctionalInterface
public interface ConversionProgressCallback {

	void update(int processed, int total, int filePercent, String currentFile);
}