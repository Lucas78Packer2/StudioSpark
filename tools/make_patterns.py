#!/usr/bin/env python3
"""Writes assets/patterns/genres.json: original StudioSpark genre pattern templates (written for this app, no MIDI files or
transcriptions used). Grid = 16 steps per bar (16th notes, 4/4). A lane/rhythm string is one bar (repeated) or bars*16 long.
Drums:  X accent  x normal  o soft/open  g ghost  . rest      lanes: kick snare rim hat ohat pedal crash ride bell china tom1 tom2 floor
Lines:  R F O T S = root / fifth / octave / third / seventh (diatonic to the key), lowercase = softer, x = muted/dead note,
        C = power chord, M = full chord (guitar), '-' = hold previous, '.' = rest.  prog = chord root per bar, semitones above the key.
"""
import json, sys, os

def d(name, bars=4, swing=0.0, fill=None, **lanes): return {"name": name, "bars": bars, "swing": swing, "lanes": lanes, **({"fill": fill} if fill else {})}
def l(name, rhythm, prog, swing=0.0): return {"name": name, "bars": len(prog), "rhythm": rhythm, "prog": prog, "swing": swing}

STD_FILL = {"snare": "....X.......xx..", "tom1": "..............x.", "floor": "...............X", "hat": "x.x.x.x.x.x....."}
GENRES = [
 {"name": "Rock", "tempo": 120, "key": 4, "minor": True, "tags": ["rock", "classic", "indie", "punk", "grunge"],
  "drums": [
    d("Straight 8ths", fill=STD_FILL, kick="X.......X.x.....", snare="....X.......X...", hat="x.x.x.x.x.x.x.x.", crash="X..............." + "." * 48),
    d("Floor stomp", kick="X...X...X...X...", snare="....X.......X...", hat="x.x.x.x.x.x.x...", ohat="..............o."),
    d("Half-time", fill=STD_FILL, kick="X.........X.....", snare="........X.......", hat="x.x.x.x.x.x.x.x."),
    d("Ride groove", kick="X.....x...X.....", snare="....X.......X...", ride="x.x.x.x.x.x.x.x.", bell="X.......X......."),
    d("Driving 16ths", fill=STD_FILL, kick="X.....X.X.......", snare="....X.......X...", hat="XxxxXxxxXxxxXxxx")],
  "bass": [l("Root eighths", "R.R.R.R.R.R.R.R.", [0, 0, 10, 5]), l("Root-fifth drive", "R.R.F.R.R.R.F.O.", [0, 3, 10, 5]),
           l("Half-time pulse", "R-------R---F---", [0, 8, 10, 0]), l("Octave bounce", "R.O.R.O.R.O.R.O.", [0, 5, 0, 7]), l("Driving gallop", "R.RrR.RrR.RrR.Rr", [0, 10, 8, 10])],
  "guitar": [l("Power chord 8ths", "C.C.C.C.C.C.C.C.", [0, 0, 10, 5]), l("Open strum", "M--M--M-M-M-M-M-", [0, 3, 10, 5]),
             l("Palm-muted chug", "x.x.x.x.C---x.x.", [0, 8, 10, 0]), l("Single-note riff", "r.r.f.r.o.r.f.t.", [0, 5, 0, 7]), l("Sustained power chords", "C-------C---C---", [0, 10, 8, 10])]},
 {"name": "Country", "tempo": 110, "key": 7, "minor": False, "tags": ["country", "folk", "americana", "bluegrass", "western", "nashville"],
  "drums": [
    d("Train beat", kick="X.......X.......", snare="gxgxgxgxgxgxgxgx"),
    d("Two-step", kick="X.......X.......", rim="....x.......x...", hat="x.x.x.x.x.x.x.x."),
    d("Shuffle", swing=0.3, fill=STD_FILL, kick="X.......X.......", snare="....X.......X...", ride="x.x.x.x.x.x.x.x."),
    d("Brush ballad", kick="X.........x.....", snare="....o.......o...", ride="o...o...o...o..."),
    d("Boom-chick", kick="X.......X.......", snare="....X.......X...", hat="x.x.x.x.x.x.x.x.", pedal="....x.......x...")],
  "bass": [l("Root-fifth alternating", "R.......F.......", [0, 5, 7, 0]), l("Walk-up", "R...F...R.T.F.O.", [0, 0, 5, 7]),
           l("Two-beat", "R-..F-..R-..F-..", [0, 9, 5, 7]), l("Train", "R.F.R.F.R.F.R.F.", [0, 5, 0, 7]), l("Ballad roots", "R-------F-------", [0, 7, 9, 5])],
  "guitar": [l("Boom-chick strum", "r...M...f...M...", [0, 5, 7, 0]), l("Train strum", "M.x.M.x.M.x.M.x.", [0, 0, 5, 7]),
             l("Chicken pickin'", "r.o.f.r.t.f.o.r.", [0, 9, 5, 7]), l("Ballad arpeggio", "r.f.o.t.o.f.r.f.", [0, 5, 0, 7]), l("Front-porch strum", "M---M-M-M---M-M-", [0, 7, 9, 5])]},
 {"name": "Metal", "tempo": 140, "key": 4, "minor": True, "tags": ["metal", "heavy", "thrash", "djent", "metalcore", "doom", "hardcore"],
  "drums": [
    d("Double-kick 16ths", kick="xxxxxxxxxxxxxxxx", snare="....X.......X...", hat="x.x.x.x.x.x.x.x.", crash="X..............." * 1 + "." * 48),
    d("Thrash skank", kick="X.x.X.x.X.x.X.x.", snare="..X...X...X...X.", hat="x.x.x.x.x.x.x.x."),
    d("Half-time breakdown", kick="X..X..X...X..X..", snare="........X.......", china="X...X...X...X..."),
    d("Gallop kicks", fill=STD_FILL, kick="X.xxX.xxX.xxX.xx", snare="....X.......X...", hat="x.x.x.x.x.x.x.x."),
    d("Blast beat", kick="x.x.x.x.x.x.x.x.", snare=".x.x.x.x.x.x.x.x", ride="x.x.x.x.x.x.x.x.")],
  "bass": [l("16th chug", "RrRrRrRrRrRrRrRr", [0, 0, 1, 0]), l("Skank eighths", "R.R.R.R.R.R.R.R.", [0, 3, 1, 0]),
           l("Breakdown", "R..R..R...R..R..", [0, 0, 10, 8]), l("Thrash gallop", "R.RrR.RrR.RrR.Rr", [0, 0, 3, 5]), l("Octave pulse", "R.O.R.O.R.O.R.O.", [0, 1, 0, 10])],
  "guitar": [l("Palm-muted 16ths", "xxxxxxxxxxxxC-C-", [0, 0, 1, 0]), l("Skank power chords", "C.C.C.C.C.C.C.C.", [0, 3, 1, 0]),
             l("Breakdown chugs", "x..x..x...x..C--", [0, 0, 10, 8]), l("Gallop riff", "C.xxC.xxC.xxC.xx", [0, 0, 3, 5]), l("Tremolo riff", "rrrrffffoooorrrr", [0, 1, 0, 10])]},
 {"name": "Pop", "tempo": 100, "key": 0, "minor": False, "tags": ["pop", "dance", "disco", "ballad", "synthpop", "edm"],
  "drums": [
    d("Pop backbeat", fill=STD_FILL, kick="X.....X.X.......", snare="....X.......X...", hat="x.x.x.x.x.x.x.x."),
    d("Dance floor", kick="X...X...X...X...", snare="....X.......X...", ohat="..o...o...o...o."),
    d("Half-time ballad", kick="X.......x.X.....", snare="........X.......", hat="x.x.x.x.x.x.x.x."),
    d("Syncopated 16ths", kick="X..X..X...X.....", snare="....X..g.g..X...", hat="xoxoxoxoxoxoxoxo"),
    d("Disco", kick="X...X...X...X...", snare="....X.......X...", hat="x.x.x.x.x.x.x.x.", ohat="..o...o...o...o.")],
  "bass": [l("Eighth pulse", "R.R.R.R.R.R.R.R.", [0, 7, 9, 5]), l("Syncopated", "R..R..R.R..R..F.", [9, 5, 0, 7]),
           l("Octave disco", "R.O.R.O.R.O.R.O.", [0, 9, 5, 7]), l("Ballad half notes", "R-------F-------", [0, 5, 9, 7]), l("Melodic", "R...T...F...O...", [5, 7, 0, 9])],
  "guitar": [l("Eighth strum", "M.M.M.M.M.M.M.M.", [0, 7, 9, 5]), l("Offbeat skank", "..M...M...M...M.", [9, 5, 0, 7]),
             l("Arpeggio", "r.t.f.o.r.t.f.o.", [0, 9, 5, 7]), l("Muted funk", "x.M.x.xMx.M.x.xM", [0, 5, 9, 7]), l("Whole-note chords", "M---------------", [5, 7, 0, 9])]},
]

def check(g):
    for k in ("drums", "bass", "guitar"): assert len(g[k]) == 5, (g["name"], k)
    for p in g["drums"]:
        for lane, s in list(p["lanes"].items()) + list(p.get("fill", {}).items()): assert len(s) in (16, 16 * p["bars"]), (g["name"], p["name"], lane)
    for k in ("bass", "guitar"):
        for p in g[k]: assert len(p["rhythm"]) in (16, 16 * p["bars"]) and 4 <= p["bars"] <= 8, (g["name"], p["name"])

for g in GENRES: check(g)
out = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/patterns/genres.json"
json.dump({"format": "studiospark-patterns/1", "grid": 16, "genres": GENRES}, open(out, "w"), indent=1)
print(out, sum(len(g[k]) for g in GENRES for k in ("drums", "bass", "guitar")), "patterns")
