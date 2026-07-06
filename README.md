# Boshys-BTE-Utils
Boshy's helpful utilities when building in the Build The Earth project. [THIS IS NOT OFFICIAL BY THE BTE DEV TEAM]
- This mod works on Minecraft version 1.21.10 on Fabric.
- This mod also requires Mod Menu and Cloth Config

## How to connect TPLL to a keybind:
- Open the controls menu within Minecraft, and set the keybind that you want to use tpll with.
- Copy the coordinates from either Google Earth pro or Google Maps. If the format is compatible with tpll, then it'll work!

## How to add a marker and connect the markers with lines:
- In the Mod Menu information about Boshys-BTE-Utils, you can see how to add a marker, and how to connect them with lines, and how to remove them.
- To add a marker, use `/boshys-bt-utils addMarker` at the position where you want the marker to be
- To remove all markers, use `/boshys-bt-utils clearMarkers`
- To remove just a single marker, right click to select the marker and press delete. You cannot select a marker when holding a block or item.
- To connect two markers manually, select the two markers you are connected and a line will appear
- You can edit the colour of the lines, and markers inside the mod settings found in ModMenu

## How to import markers from Google Earth KML Files:
- In the Mod Menu section labeled "KML Importing", you can change the file path where KML Files are detected from. You can also find this file path using `/boshys-bt-utils markerFileLocation`
- To start importing the KML, use `/boshys-bt-utils importKML <file>`, and make sure you don't move until the import is finished, or the tpll markers could be placed in the wrong area.
- You can import multiple KML files using `/boshys-bt-utils importMultipleKMLs <file>...`
- To stop KML imports from happening, you can use `/boshys-bt-utils stopImport`. This will cancel all imports queued and currently importing.