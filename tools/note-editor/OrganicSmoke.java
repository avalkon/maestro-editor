package com.digero.maestro.noteedit;
import javax.sound.midi.*;
import java.nio.file.*;
import java.io.*;
public class OrganicSmoke {
 static void check(boolean yes,String label){if(!yes)throw new AssertionError(label);}
 public static void main(String[]args)throws Exception{
  File input=new File(args[0]);
  String source=Files.readString(input.toPath());
  NoteDocument abc=AbcDocumentIO.read(input);
  check(abc.layers.size()==10,"expected 10 parts");
  check(abc.layer(0).notes.size()>100,"expected notes from large L");
  check(abc.layer(0).abcTempo==140,"ABC tempo 140");
  Sequence selected=EditorPlayback.sequenceFor(abc,0), all=EditorPlayback.sequenceFor(abc,-1);
  check(selected.getTracks().length==2,"one part playing");
  check(all.getTracks().length==11,"all parts playing");
  check(all.getTickLength()>0,"ABC preview sequence has length");
  EditableNote n=abc.layer(0).notes.getFirst();
  abc.edit(0,list->list.set(0,n.move(2,0)));
  File output=Files.createTempFile("maestro-organic-test", ".abc").toFile();
  AbcDocumentIO.write(abc,output);
  String result=Files.readString(output.toPath());
  check(result.substring(result.indexOf("X: 11")).equals(source.substring(source.indexOf("X: 11"))),"all other parts unchanged");
  NoteDocument loaded=AbcDocumentIO.read(output);
  check(loaded.layer(0).notes.getFirst().pitch()==n.pitch()+2,"edited pitch round trips");
  check(loaded.layers.size()==10,"parts survive");
  File giant=Files.createTempFile("maestro-large-L", ".abc").toFile();
  Files.writeString(giant.toPath(),"X:1\nT:Extreme L\nL:1/4000000\nM:4/4\nQ:100\nK:C\nz4000000 C500000 D300000 |]\n");
  NoteDocument huge=AbcDocumentIO.read(giant);
  check(huge.layer(0).notes.size()==2,"4-million L parsed");
  check(huge.layer(0).notes.getFirst().start()==4*AbcDocumentIO.PPQ,"rest length absolute correct");
  check(huge.layer(0).abcTempo==100,"tempo parsed");
  check(EditorPlayback.sequenceFor(huge,-1).getTickLength()>0,"giant L plays");
  System.out.println("PASS organic L large ABC: "+abc.layers.size()+" parts; "+result.length()+" bytes edited output; preview "+all.getTickLength()+" ticks");
 }
}
