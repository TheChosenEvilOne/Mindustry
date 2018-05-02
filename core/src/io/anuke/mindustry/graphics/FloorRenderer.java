package io.anuke.mindustry.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.IntSet;
import com.badlogic.gdx.utils.IntSet.IntSetIterator;
import com.badlogic.gdx.utils.async.AsyncExecutor;
import io.anuke.mindustry.game.EventType.WorldLoadEvent;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Tile;
import io.anuke.ucore.core.Core;
import io.anuke.ucore.core.Events;
import io.anuke.ucore.core.Graphics;
import io.anuke.ucore.core.Timers;
import io.anuke.ucore.graphics.CacheBatch;
import io.anuke.ucore.graphics.Draw;
import io.anuke.ucore.util.Log;
import io.anuke.ucore.util.Mathf;

import java.util.Arrays;

import static io.anuke.mindustry.Vars.tilesize;
import static io.anuke.mindustry.Vars.world;

public class FloorRenderer {
    private final static int vsize = 4;
    private final static int chunksize = 32;

    private AsyncExecutor executor = new AsyncExecutor(8);
    private Chunk[][] cache;
    private CacheBatch cbatch;
    private IntSet drawnLayerSet = new IntSet();
    private IntArray drawnLayers = new IntArray();

    public FloorRenderer(){
        Events.on(WorldLoadEvent.class, this::clearTiles);
    }

    public void drawFloor(){

        OrthographicCamera camera = Core.camera;

        int crangex = (int)(camera.viewportWidth * camera.zoom / (chunksize * tilesize))+1;
        int crangey = (int)(camera.viewportHeight * camera.zoom / (chunksize * tilesize))+1;

        for(int x = -crangex; x <= crangex; x++) {
            for (int y = -crangey; y <= crangey; y++) {
                int worldx = Mathf.scl(camera.position.x, chunksize * tilesize) + x;
                int worldy = Mathf.scl(camera.position.y, chunksize * tilesize) + y;

                if(!Mathf.inBounds(worldx, worldy, cache))
                    continue;

                fillChunk(worldx * chunksize * tilesize, worldy * chunksize * tilesize);
            }
        }

        int layers = DrawLayer.values().length;

        drawnLayers.clear();
        drawnLayerSet.clear();

        //preliminary layer check:
        for(int x = -crangex; x <= crangex; x++){
            for(int y = -crangey; y <= crangey; y++){
                int worldx = Mathf.scl(camera.position.x, chunksize * tilesize) + x;
                int worldy = Mathf.scl(camera.position.y, chunksize * tilesize) + y;

                if (!Mathf.inBounds(worldx, worldy, cache))
                    continue;

                Chunk chunk = cache[worldx][worldy];

                //loop through all layers, and add layer index if it exists
                for(int i = 0; i < layers - 1; i ++){
                    if(chunk.caches[i] != -1){
                        drawnLayerSet.add(i);
                    }
                }
            }
        }

        IntSetIterator it = drawnLayerSet.iterator();
        while(it.hasNext){
            drawnLayers.add(it.next());
        }

        drawnLayers.sort();

        Graphics.end();
        beginDraw();

        for(int i = 0; i < drawnLayers.size; i ++) {
            DrawLayer layer = DrawLayer.values()[drawnLayers.get(i)];

            drawLayer(layer);
        }

        endDraw();
        Graphics.begin();
    }

    public void beginDraw(){
        cbatch.setProjectionMatrix(Core.camera.combined);
        cbatch.beginDraw();

        Gdx.gl.glEnable(GL20.GL_BLEND);
    }

    public void endDraw(){
        cbatch.endDraw();
    }

    public void drawLayer(DrawLayer layer){
        OrthographicCamera camera = Core.camera;

        int crangex = (int)(camera.viewportWidth * camera.zoom / (chunksize * tilesize))+1;
        int crangey = (int)(camera.viewportHeight * camera.zoom / (chunksize * tilesize))+1;

        layer.begin();

        for (int x = -crangex; x <= crangex; x++) {
            for (int y = -crangey; y <= crangey; y++) {
                int worldx = Mathf.scl(camera.position.x, chunksize * tilesize) + x;
                int worldy = Mathf.scl(camera.position.y, chunksize * tilesize) + y;

                if(!Mathf.inBounds(worldx, worldy, cache)){
                    continue;
                }

                Chunk chunk = cache[worldx][worldy];
                cbatch.drawCache(chunk.caches[layer.ordinal()]);
            }
        }

        layer.end();
    }

    private void fillChunk(float x, float y){
        Draw.color(Color.GRAY);
        Draw.crect("white", x, y, chunksize * tilesize, chunksize * tilesize);
        Draw.color();
    }

    private void cacheChunk(int cx, int cy){
        Chunk chunk = cache[cx][cy];

        for(DrawLayer layer : DrawLayer.values()){
            cacheChunkLayer(cx, cy, chunk, layer);
        }
    }

    private void cacheChunkLayer(int cx, int cy, Chunk chunk, DrawLayer layer){

        Graphics.useBatch(cbatch);
        cbatch.begin();

        for(int tilex = cx * chunksize; tilex < (cx + 1) * chunksize; tilex++){
            for(int tiley = cy * chunksize; tiley < (cy + 1) * chunksize; tiley++){
                Tile tile = world.tile(tilex, tiley);
                if(tile == null) continue;

                if(tile.floor().drawLayer == layer && tile.block().drawLayer != DrawLayer.walls){
                    tile.floor().draw(tile);
                }else if(tile.floor().drawLayer.ordinal() < layer.ordinal() && tile.block().drawLayer != DrawLayer.walls && layer != DrawLayer.walls){
                    tile.floor().drawNonLayer(tile);
                }

                if(tile.block().drawLayer == layer && layer == DrawLayer.walls){
                    Block block = tile.block();
                    block.draw(tile);
                }
            }
        }

        cbatch.end();
        Graphics.popBatch();
        chunk.caches[layer.ordinal()] = cbatch.getLastCache();
    }

    private class Chunk{
        int[] caches = new int[DrawLayer.values().length];
    }

    public void clearTiles(){
        if(cbatch != null) cbatch.dispose();

        Timers.mark();

        int chunksx = world.width() / chunksize, chunksy = world.height() / chunksize;
        cache = new Chunk[chunksx][chunksy];
        cbatch = new CacheBatch(world.width()*world.height()*4*4);

        Log.info("Time to create: {0}", Timers.elapsed());

        for (int x = 0; x < chunksx; x++) {
            for (int y = 0; y < chunksy; y++) {
                cache[x][y] = new Chunk();
                Arrays.fill(cache[x][y].caches, -1);

                cacheChunk(x, y);
            }
        }

        Log.info("Time to cache: {0}", Timers.elapsed());
    }

    static ShaderProgram createDefaultShader () {
        String vertexShader = "attribute vec4 " + ShaderProgram.POSITION_ATTRIBUTE + ";\n" //
                + "attribute vec2 " + ShaderProgram.TEXCOORD_ATTRIBUTE + "0;\n" //
                + "uniform mat4 u_projTrans;\n" //
                + "varying vec2 v_texCoords;\n" //
                + "\n" //
                + "void main()\n" //
                + "{\n" //
                + "   v_texCoords = " + ShaderProgram.TEXCOORD_ATTRIBUTE + "0;\n" //
                + "   gl_Position =  u_projTrans * " + ShaderProgram.POSITION_ATTRIBUTE + ";\n" //
                + "}\n";
        String fragmentShader = "#ifdef GL_ES\n" //
                + "#define LOWP lowp\n" //
                + "precision mediump float;\n" //
                + "#else\n" //
                + "#define LOWP \n" //
                + "#endif\n" //
                + "varying vec2 v_texCoords;\n" //
                + "uniform sampler2D u_texture;\n" //
                + "void main()\n"//
                + "{\n" //
                + "  gl_FragColor = texture2D(u_texture, v_texCoords);\n" //
                + "}";

        ShaderProgram shader = new ShaderProgram(vertexShader, fragmentShader);
        if (!shader.isCompiled()) throw new IllegalArgumentException("Error compiling shader: " + shader.getLog());
        return shader;
    }
}