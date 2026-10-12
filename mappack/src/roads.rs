//! Which OpenStreetMap ways go into the proof index, and in which directions each vehicle may travel them.
//!
//! These rules are part of the proof index format: every rebuild must apply exactly them, so they read tags
//! and nothing else. They are deliberately generous. The index says what is legal at most; Valhalla, with its
//! own finer rules, picks routes inside that. A way Valhalla would use but the index leaves out would make
//! honest routes fail, while a way the index allows and Valhalla never uses costs nothing.
//!
//! Turn restrictions are relations and are not in the index.

use klippelgo_verify::index::{CAR_BACKWARD, CAR_FORWARD, MOTORCYCLE_BACKWARD, MOTORCYCLE_FORWARD};

/// `highway` values motor vehicles drive on unless a tag says otherwise.
const ROAD_CLASSES: [&str; 17] = [
    "motorway",
    "motorway_link",
    "trunk",
    "trunk_link",
    "primary",
    "primary_link",
    "secondary",
    "secondary_link",
    "tertiary",
    "tertiary_link",
    "unclassified",
    "residential",
    "residential_link",
    "living_street",
    "service",
    "road",
    "track",
];

/// `highway` values closed to motor vehicles unless a tag opens them, such as a pedestrian street tagged
/// `motor_vehicle=permissive`.
const PATH_CLASSES: [&str; 8] = [
    "footway",
    "pedestrian",
    "path",
    "cycleway",
    "bridleway",
    "busway",
    "bus_guideway",
    "platform",
];

/// Access values that close a way to the general public's vehicles. Anything else (`private`, `destination`,
/// `customers`, `permissive`, ...) can still be the first or last stretch of a ride.
const DENIED: [&str; 7] = [
    "no",
    "agricultural",
    "forestry",
    "emergency",
    "military",
    "discouraged",
    "use_sidepath",
];

pub fn is_road(highway: Option<&str>) -> bool {
    highway.is_some_and(|h| ROAD_CLASSES.contains(&h))
}

/// The proof-index flags for a way's tags, or 0 when no motor vehicle may use it.
pub fn flags<'a>(tag: impl Fn(&str) -> Option<&'a str>) -> u8 {
    let road = is_road(tag("highway"));
    if !(road || tag("highway").is_some_and(|h| PATH_CLASSES.contains(&h)))
        || tag("area") == Some("yes")
    {
        return 0;
    }
    // Valhalla routes every vehicle over a driveway that has no access tag of its own, whatever else it says.
    let driveway = road && tag("service") == Some("driveway") && tag("access").is_none();
    // The most specific tag present decides: access < vehicle < motor_vehicle < motorcar / motorcycle. A road
    // is open unless that tag closes it. A path is closed unless a vehicle tag opens it; `access=yes` alone
    // does not put cars on a footway.
    let allowed = |own: &str| {
        let specific = [own, "motor_vehicle", "vehicle"].into_iter().find_map(&tag);
        match (specific, road) {
            _ if driveway => true,
            (Some(value), _) => !DENIED.contains(&value),
            (None, true) => tag("access").is_none_or(|value| !DENIED.contains(&value)),
            (None, false) => false,
        }
    };
    let (forward, backward) = match tag("oneway") {
        Some("yes" | "true" | "1") => (true, false),
        Some("-1" | "reverse") => (false, true),
        Some(_) => (true, true),
        // Motorways and roundabouts are one-way unless tagged otherwise.
        None => {
            let implied = tag("highway") == Some("motorway")
                || matches!(tag("junction"), Some("roundabout" | "circular"));
            (true, !implied)
        }
    };
    let mut flags = 0;
    if allowed("motorcar") {
        flags |= if forward { CAR_FORWARD } else { 0 } | if backward { CAR_BACKWARD } else { 0 };
    }
    if allowed("motorcycle") {
        flags |= if forward { MOTORCYCLE_FORWARD } else { 0 }
            | if backward { MOTORCYCLE_BACKWARD } else { 0 };
    }
    flags
}

#[cfg(test)]
mod tests {
    use super::*;

    fn of(tags: &[(&'static str, &'static str)]) -> u8 {
        flags(|key| tags.iter().find(|(k, _)| *k == key).map(|(_, v)| *v))
    }

    const BOTH_WAYS: u8 = CAR_FORWARD | CAR_BACKWARD | MOTORCYCLE_FORWARD | MOTORCYCLE_BACKWARD;
    const FORWARD: u8 = CAR_FORWARD | MOTORCYCLE_FORWARD;

    #[test]
    fn roads_are_two_way_unless_tagged_or_implied() {
        assert_eq!(of(&[("highway", "residential")]), BOTH_WAYS);
        assert_eq!(of(&[("highway", "primary"), ("oneway", "yes")]), FORWARD);
        assert_eq!(
            of(&[("highway", "primary"), ("oneway", "-1")]),
            CAR_BACKWARD | MOTORCYCLE_BACKWARD
        );
        assert_eq!(of(&[("highway", "primary"), ("oneway", "no")]), BOTH_WAYS);
        assert_eq!(of(&[("highway", "motorway")]), FORWARD);
        assert_eq!(of(&[("highway", "motorway"), ("oneway", "no")]), BOTH_WAYS);
        assert_eq!(
            of(&[("highway", "tertiary"), ("junction", "roundabout")]),
            FORWARD
        );
    }

    #[test]
    fn only_roads_for_motor_vehicles_are_listed() {
        for class in [
            "footway",
            "cycleway",
            "pedestrian",
            "path",
            "steps",
            "construction",
            "busway",
        ] {
            assert_eq!(of(&[("highway", class)]), 0, "{class}");
            assert_eq!(of(&[("highway", class), ("access", "yes")]), 0, "{class}");
        }
        // A crossing or pedestrian street that is explicitly open to motor traffic.
        assert_eq!(
            of(&[("highway", "footway"), ("motor_vehicle", "permissive")]),
            BOTH_WAYS
        );
        assert_eq!(
            of(&[("highway", "pedestrian"), ("motorcycle", "yes")]),
            MOTORCYCLE_FORWARD | MOTORCYCLE_BACKWARD
        );
        assert_eq!(of(&[("highway", "steps"), ("motor_vehicle", "yes")]), 0);
        assert_eq!(of(&[("building", "yes")]), 0);
        assert_eq!(of(&[("highway", "service"), ("area", "yes")]), 0);
    }

    #[test]
    fn the_most_specific_access_tag_wins() {
        assert_eq!(of(&[("highway", "residential"), ("access", "no")]), 0);
        assert_eq!(
            of(&[("highway", "residential"), ("access", "private")]),
            BOTH_WAYS
        );
        assert_eq!(
            of(&[("highway", "residential"), ("motor_vehicle", "no")]),
            0
        );
        assert_eq!(
            of(&[
                ("highway", "residential"),
                ("access", "no"),
                ("motorcycle", "yes")
            ]),
            MOTORCYCLE_FORWARD | MOTORCYCLE_BACKWARD
        );
        assert_eq!(
            of(&[("highway", "residential"), ("motorcar", "no")]),
            MOTORCYCLE_FORWARD | MOTORCYCLE_BACKWARD
        );
        assert_eq!(
            of(&[
                ("highway", "trunk"),
                ("motorcycle", "no"),
                ("oneway", "yes")
            ]),
            CAR_FORWARD
        );
        assert_eq!(
            of(&[
                ("highway", "service"),
                ("vehicle", "no"),
                ("motor_vehicle", "yes")
            ]),
            BOTH_WAYS
        );
        // Driveways are the last metres of a trip: open to all unless the driveway itself has an access tag.
        assert_eq!(
            of(&[
                ("highway", "service"),
                ("service", "driveway"),
                ("motorcycle", "no"),
                ("oneway", "yes")
            ]),
            FORWARD
        );
        assert_eq!(
            of(&[
                ("highway", "service"),
                ("service", "driveway"),
                ("access", "no")
            ]),
            0
        );
    }
}
