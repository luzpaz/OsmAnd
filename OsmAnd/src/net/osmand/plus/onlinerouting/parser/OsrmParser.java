package net.osmand.plus.onlinerouting.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RouteDirectionInfo;
import net.osmand.router.TurnType;
import net.osmand.util.GeoPolylineParserUtil;
import net.osmand.util.MapUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static net.osmand.util.Algorithms.isEmpty;
import static net.osmand.util.Algorithms.objectEquals;

public class OsrmParser extends JSONParser {

	@Nullable
	@Override
	protected OnlineRoutingResponse parseServerResponse(@NonNull JSONObject root,
	                                                    @NonNull OsmandApplication app,
	                                                    boolean leftSideNavigation) throws JSONException {
		String encodedPoints = root.getString("geometry");
		List<LatLon> points = GeoPolylineParserUtil.parse(encodedPoints, GeoPolylineParserUtil.PRECISION_5);
		if (isEmpty(points)) return null;

		List<Location> route = convertRouteToLocationsList(points);
		List<RouteDirectionInfo> directions = new ArrayList<>();
		int startSearchingId = 0;
		JSONArray legs = root.getJSONArray("legs");
		for (int i = 0; i < legs.length(); i++) {
			JSONObject leg = legs.getJSONObject(i);
			if (!leg.has("steps")) continue;

			JSONArray steps = leg.getJSONArray("steps");
			for (int j = 0; j < steps.length(); j++) {
				JSONObject instruction = steps.getJSONObject(j);
				JSONObject maneuver = instruction.getJSONObject("maneuver");
				String maneuverType = maneuver.getString("type");

				JSONArray location = maneuver.getJSONArray("location");
				double lon = location.getDouble(0);
				double lat = location.getDouble(1);
				Integer routePointOffset = getLocationIndexInList(route, startSearchingId, lat, lon);
				if (routePointOffset == null) continue;
				startSearchingId = routePointOffset;

				// in meters
				int distance = (int) Math.round(instruction.getDouble("distance"));
				// in seconds
				int duration = (int) Math.round(instruction.getDouble("duration"));

				float averageSpeed = (float) distance / duration;
				TurnType turnType = parseTurnType(maneuver, leftSideNavigation);
				RouteDirectionInfo direction = new RouteDirectionInfo(averageSpeed, turnType);
				direction.setDistance(distance);

				String streetName = instruction.getString("name");
				String description = "";
				if (!objectEquals(maneuverType, "arrive")) {
					description = RouteCalculationResult.toString(turnType, app, false) + " " + streetName;
				}
				description = description.trim();

				direction.setStreetName(streetName);
				direction.setDescriptionRoute(description);
				direction.routePointOffset = routePointOffset;
				directions.add(direction);
			}
		}

		return new OnlineRoutingResponse(route, directions);
	}

	@Nullable
	private Integer getLocationIndexInList(@NonNull List<Location> locations,
	                                       int startIndex, double lat, double lon) {
		for (int i = startIndex; i < locations.size(); i++) {
			Location l = locations.get(i);
			if (MapUtils.areLatLonEqual(l, lat, lon)) {
				return i;
			}
		}
		return null;
	}

	@NonNull
	private TurnType parseTurnType(@NonNull JSONObject maneuver,
	                               boolean leftSide) throws JSONException {
		TurnType turnType = null;

		String type = maneuver.getString("type");
		String modifier = null;
		if (maneuver.has("modifier")) {
			modifier = maneuver.getString("modifier");
		}

		if (objectEquals(type, "roundabout")
				|| objectEquals(type, "rotary")
				|| objectEquals(type, "roundabout turn")) {
			if (maneuver.has("exit")) {
				int exit = maneuver.getInt("exit");
				turnType = TurnType.getExitTurn(exit, 0.0f, leftSide);
			} else if (modifier != null) {
				// for simple roundabout turn without "exit" parameter
				turnType = identifyTurnType(modifier, leftSide);
			}
		} else {
			// for other maneuver types find TurnType
			// like a basic turn into direction of the modifier
			if (modifier != null) {
				turnType = identifyTurnType(modifier, leftSide);
			}
		}
		if (turnType == null) {
			turnType = TurnType.straight();
		}

		int bearingBefore = maneuver.getInt("bearing_before");
		int bearingAfter = maneuver.getInt("bearing_after");
		float angle = (float) MapUtils.degreesDiff(bearingAfter, bearingBefore);
		turnType.setTurnAngle(angle);

		return turnType;
	}

	@Nullable
	private TurnType identifyTurnType(@NonNull String modifier,
	                                  boolean leftSide) {
		Integer id = null;
		switch (modifier) {
			case "uturn":
				id = TurnType.TU;
				break;

			case "sharp right":
				id = TurnType.TSHR;
				break;

			case "right":
				id = TurnType.TR;
				break;

			case "slight right":
				id = TurnType.TSLR;
				break;

			case "straight":
				id = TurnType.C;
				break;

			case "slight left":
				id = TurnType.TSLL;
				break;

			case "left":
				id = TurnType.TL;
				break;

			case "sharp left":
				id = TurnType.TSHL;
				break;
		}
		return id != null ? TurnType.valueOf(id, leftSide) : null;
	}

	@NonNull
	@Override
	protected String getRootArrayKey() {
		return "routes";
	}

	@NonNull
	@Override
	protected String getErrorMessageKey() {
		return "message";
	}
}
