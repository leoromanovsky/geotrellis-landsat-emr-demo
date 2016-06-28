package demo

import demo.etl.landsat.{LandsatModule, TemporalMultibandLandsatInput}

import geotrellis.vector.io._
import geotrellis.raster._
import geotrellis.raster.resample._
import geotrellis.spark._
import geotrellis.spark.io.{Writer, _}
import geotrellis.spark.etl.{Etl, OutputPlugin}
import geotrellis.spark.etl.config.EtlConf
import geotrellis.spark.util._
import geotrellis.spark.tiling._
import geotrellis.spark.pyramid._
import geotrellis.proj4._

import spray.json.DefaultJsonProtocol._
import org.apache.spark._
import org.apache.spark.rdd._

object LandsatIngest extends Logging {

  /** Accept a list of landsat image descriptors we will ingest into a geotrellis layer.
    * It is expected that the user will generate this list using `Landsat8Query` and prefilter.
    */
  def run(
    layerName: String,
    reprojected: RDD[(TemporalProjectedExtent, MultibandTile)],
    inputPlugin: TemporalMultibandLandsatInput,
    writer: Writer[LayerId, RDD[(SpaceTimeKey, MultibandTile)] with Metadata[TileLayerMetadata[SpaceTimeKey]]],
    attributeStore: AttributeStore
  )(implicit sc: SparkContext): Unit = {
    // Our dataset can span UTM zones, we must reproject the tiles individually to common projection
    val maxZoom = 13 // We know this ahead of time based on Landsat resolution
    val destCRS = WebMercator
    val resampleMethod = Bilinear
    val layoutScheme = ZoomedLayoutScheme(destCRS, 256)
    val tileLayerMetadata = inputPlugin.calculateTileLayerMetadata(maxZoom, destCRS)
    logger.info("sTileLayerMetadata calculated: $tileLayerMetadata")
    val tiledRdd = reprojected.tileToLayout(tileLayerMetadata, resampleMethod)
    val rdd = new ContextRDD(tiledRdd, tileLayerMetadata)

    Pyramid.upLevels(rdd, layoutScheme, maxZoom, 1, resampleMethod){ (rdd: RDD[(SpaceTimeKey, MultibandTile)] with Metadata[TileLayerMetadata[SpaceTimeKey]], zoom) =>
      writer.write(LayerId(layerName, zoom), rdd)

      if (zoom == 1) {
        // Store attributes common across zooms for catalog to see
        val id = LayerId(layerName, 0)
        attributeStore.write(id, "times",
          rdd
            .map(_._1.instant)
            .countByValue
            .keys.toArray
            .sorted)
        attributeStore.write(id, "extent",
          (rdd.metadata.extent, rdd.metadata.crs))
      }
    }
  }
}

object LandsatIngestMain extends Logging {

  def main(args: Array[String]): Unit = {
    logger.info(s"Arguments: ${args.toSeq}")
    implicit val sc = SparkUtils.createSparkContext("GeoTrellis Landsat Ingest", new SparkConf(true))

    EtlConf(args).getEtlJobs.foreach { job =>
      val etl = Etl(job, Etl.defaultModules :+ LandsatModule)
      val inputPlugin = new TemporalMultibandLandsatInput()
      val sourceTiles = inputPlugin(job)

      val outputPlugin = etl.combinedModule
        .findSubclassOf[OutputPlugin[SpaceTimeKey, MultibandTile, TileLayerMetadata[SpaceTimeKey]]]
        .find { _.suitableFor(job.config.ingestType.output.name) }
        .getOrElse(sys.error(s"Unable to find output module of type '${job.config.ingestType.output.name}'"))

      /* TODO if the layer exists the ingest will fail, we need to use layer updater*/
      LandsatIngest.run(
        layerName      = job.config.name,
        reprojected    = sourceTiles,
        inputPlugin    = inputPlugin,
        writer         = outputPlugin.writer(job),
        attributeStore = outputPlugin.attributes(job))
    }

    sc.stop()
  }
}
