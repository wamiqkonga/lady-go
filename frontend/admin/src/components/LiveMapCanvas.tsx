import { useEffect, useRef } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { MAP_ATTRIBUTION, MAP_TILE_URL } from '../config'

export type MapMarkerKind = 'driver' | 'driver-busy' | 'pickup' | 'drop' | 'incident'

export interface MapMarker {
  id: string
  kind: MapMarkerKind
  lat: number
  lon: number
  label: string
  stale?: boolean
}

interface Props {
  markers: MapMarker[]
  selectedId: string | null
  onSelect: (id: string) => void
}

// Kashmir pilot area: used until the first markers arrive.
const DEFAULT_CENTER: L.LatLngTuple = [34.0837, 74.7973]
const GLYPH: Record<MapMarkerKind, string> = { driver: 'D', 'driver-busy': 'D', pickup: 'P', drop: 'X', incident: '!' }

function iconFor(marker: MapMarker, selected: boolean): L.DivIcon {
  const classes = ['map-pin', `map-pin-${marker.kind}`]
  if (marker.stale) classes.push('map-pin-stale')
  if (selected) classes.push('map-pin-selected')
  return L.divIcon({ className: classes.join(' '), html: GLYPH[marker.kind], iconSize: [28, 28], iconAnchor: [14, 14] })
}

/** Thin Leaflet wrapper: draws markers, reports clicks, pans to the selected one. All state lives in LiveMap. */
export default function LiveMapCanvas({ markers, selectedId, onSelect }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const layerRef = useRef<L.LayerGroup | null>(null)
  const fittedRef = useRef(false)
  const onSelectRef = useRef(onSelect)
  onSelectRef.current = onSelect

  useEffect(() => {
    if (!containerRef.current) return
    const map = L.map(containerRef.current).setView(DEFAULT_CENTER, 12)
    L.tileLayer(MAP_TILE_URL, { attribution: MAP_ATTRIBUTION, maxZoom: 19 }).addTo(map)
    mapRef.current = map
    layerRef.current = L.layerGroup().addTo(map)
    return () => {
      map.remove()
      mapRef.current = null
      layerRef.current = null
      fittedRef.current = false
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    const layer = layerRef.current
    if (!map || !layer) return
    layer.clearLayers()
    const points: L.LatLngTuple[] = []
    for (const marker of markers) {
      const point: L.LatLngTuple = [marker.lat, marker.lon]
      points.push(point)
      L.marker(point, { icon: iconFor(marker, marker.id === selectedId), title: marker.label, keyboard: true })
        .on('click', () => onSelectRef.current(marker.id))
        .addTo(layer)
    }
    // Fit once on the first non-empty data; after that the admin's own pan/zoom is left alone.
    if (!fittedRef.current && points.length > 0) {
      map.fitBounds(L.latLngBounds(points), { padding: [40, 40], maxZoom: 15 })
      fittedRef.current = true
    }
  }, [markers, selectedId])

  useEffect(() => {
    const map = mapRef.current
    const selected = markers.find((m) => m.id === selectedId)
    if (map && selected) map.panTo([selected.lat, selected.lon])
    // Pan only when the selection changes, not on every poll.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  return <div ref={containerRef} className="live-map-canvas" role="region" aria-label="Map" />
}
