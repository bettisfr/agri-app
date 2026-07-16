/*
  AgriApp field enclosure - first prototype.

  Units: millimeters.
  Render one part at a time by changing `part`.

  part = "box";
  part = "lid";
  part = "assembly";

  `base` is still accepted as an alias for `box`.
*/

include <vendor/SBC_Model_Framework/sbc_models.scad>

/* [View] */
part = "assembly"; // [assembly, box, lid]
rpi_placeholder = "framework"; // [framework, simple, off]

// ---------------- Parameters ----------------
wall = 3.0;
clearance = 0.35;

box_w = 154;
box_d = 128;
box_h = 42;
corner_r = 5;

lid_h = 5;
lid_lip_h = 4;
lid_lip_inset = wall + 0.35;
screw_d = 3.2;
screw_post_d = 8.5;
lid_groove_edge_offset = 1.0;
lid_groove_w = 2.0;
lid_groove_d = 3.0;
lid_m4_hole_d = 4.3;
lid_m4_hole_offset = 5.5;
lid_pocket_gap_from_groove = 2.0;
lid_pocket_track_w = 8.0;
lid_center_cut_margin = -0.8;
lid_top_skin = 1.5;
gasket_w = 2.0;
gasket_gap = 2.2;
gasket_inset = wall + 1.0;
lid_gasket_wall_w = 1.5;
lid_gasket_wall_h = 8.0;
lid_inner_plug_w = 7.0;
box_seal_rib_w = 1.4;
box_seal_rib_h = 1.5;
box_seal_clearance = 0.35;
box_seal_base_h = 2.0;
box_m4_boss_w = 10.0;
box_m4_boss_top_clearance = 0.0;
box_m4_insert_pilot_d = 5.0;
box_corner_fill_w = 8.0;

// Raspberry Pi 5 placeholder and mounting pattern.
rpi_w = 85;
rpi_d = 56;
rpi_hole_dx = 58;
rpi_hole_dy = 49;
rpi_hole_from_left = 3.5;
rpi_hole_from_front = 3.5;
rpi_pcb_t = 1.6;
rpi_board_x = 36;
rpi_board_y = 15;
rpi_rotation_deg = 180;
rpi_standoff_h = 6;
rpi_standoff_d = 6.5;
rpi_screw_d = 2.8;

// Visual-only RPi5 connector placeholders.
// Main coordinates follow hominoids/SBC_Model_Framework `rpi5` data
// (OEM mechanical drawings + STEP model): USB-C at x=6.75,y=-1;
// micro-HDMI at x=22.5/35.85,y=-1; MIPI FFC at x=47/53.25,y=1;
// GPIO at x=7,y=50; board holes at (3.5,3.5)..(61.5,52.5).
rpi_gpio_w = 51;
rpi_gpio_d = 5;
rpi_gpio_h = 8.5;
rpi_usbc_w = 9;
rpi_usbc_d = 7;
rpi_usbc_h = 3.5;
rpi_hdmi_w = 8.5;
rpi_hdmi_d = 7;
rpi_hdmi_h = 3.2;
rpi_mipi_rows = 22;
rpi_mipi_pitch = 0.5;
rpi_mipi_body_adj = 1.9;
rpi_mipi_w = rpi_mipi_body_adj + rpi_mipi_rows * rpi_mipi_pitch;
rpi_mipi_d = 3.5;
rpi_mipi_h = 2.2;

// Camera Module 3 board is about 25 x 24 mm.
cam_board_w = 25;
cam_board_h = 24;
cam_lens_d = 13;
cam_opening_d = 16;
cam_screw_gap_x = 21;
cam_screw_gap_z = 12.5;
cam_screw_d = 2.2;
cam_opening_margin_from_screw_centers = 4.5;
cam_opening_w = cam_screw_gap_x - 2 * cam_opening_margin_from_screw_centers;
cam_opening_h = 18;
cam_opening_z_offset = -1.5;
// Align the camera lens axis with the actual centerline of the rotated MIPI FFC connectors.
cam_side_y = rpi_board_y + rpi_d - (1 + rpi_mipi_w / 2);
cam_square_opening = cam_screw_gap_x - 2 * cam_opening_margin_from_screw_centers;
cam_window_pocket_size = 20.3;
cam_window_pocket_depth = 1.3;
cam_plate_t = 4;
cam_plate_side_margin = 3;
cam_plate_top_margin = 5;

// GPS zone on lid. Kept as a shallow label/recess, not a sealed antenna cage.
gps_zone_w = 38;
gps_zone_d = 28;
gps_zone_h = 1.2;

$fn = 48;

// ---------------- Helpers ----------------
module rounded_box(size, r) {
    w = size[0];
    d = size[1];
    h = size[2];
    hull() {
        for (x = [r, w - r])
            for (y = [r, d - r])
                translate([x, y, 0])
                    cylinder(r = r, h = h);
    }
}

module screw_post(x, y, h, post_d = screw_post_d, hole_d = screw_d) {
    translate([x, y, 0])
        difference() {
            cylinder(d = post_d, h = h);
            translate([0, 0, -0.1])
                cylinder(d = hole_d, h = h + 0.2);
        }
}

module rpi_standoff(x, y) {
    translate([x, y, wall - 0.15])
        difference() {
            cylinder(d = rpi_standoff_d, h = rpi_standoff_h + 0.15);
            translate([0, 0, -0.1])
                cylinder(d = rpi_screw_d, h = rpi_standoff_h + 0.45);
        }
}

module perimeter_rect_frame(w, d, frame_w, h) {
    difference() {
        cube([w, d, h]);
        translate([frame_w, frame_w, -0.1])
            cube([w - 2 * frame_w, d - 2 * frame_w, h + 0.2]);
    }
}

module groove_segment(x1, y1, x2, y2, w, h, zfudge = 0.0) {
    dx = x2 - x1;
    dy = y2 - y1;
    len = sqrt(dx * dx + dy * dy);
    ang = atan2(dy, dx);
    translate([x1, y1, zfudge])
        rotate([0, 0, ang])
            translate([0, -w / 2, 0])
                cube([len, w, h]);
}

module groove_node(x, y, w, h, zfudge = 0.0) {
    translate([x, y, zfudge])
        cylinder(d = w, h = h);
}

function snake_outer_center() = lid_groove_edge_offset + lid_groove_w / 2;
function snake_inner_center() = lid_m4_hole_offset + lid_m4_hole_d / 2 + 2.0;

function snake_points() = [
    [snake_inner_center(), snake_outer_center()], [box_w - snake_inner_center(), snake_outer_center()],
    [box_w - snake_inner_center(), snake_inner_center()], [box_w - snake_outer_center(), snake_inner_center()],
    [box_w - snake_outer_center(), box_d - snake_inner_center()], [box_w - snake_inner_center(), box_d - snake_inner_center()],
    [box_w - snake_inner_center(), box_d - snake_outer_center()], [snake_inner_center(), box_d - snake_outer_center()],
    [snake_inner_center(), box_d - snake_inner_center()], [snake_outer_center(), box_d - snake_inner_center()],
    [snake_outer_center(), snake_inner_center()], [snake_inner_center(), snake_inner_center()],
    [snake_inner_center(), snake_outer_center()]
];

module snake_path(w, h, z = 0, zfudge = 0.0) {
    pts = snake_points();

    translate([0, 0, z]) {
        for (k = [0 : len(pts) - 2])
            groove_segment(pts[k][0], pts[k][1], pts[k + 1][0], pts[k + 1][1], w, h, zfudge);

        for (k = [0 : len(pts) - 1])
            groove_node(pts[k][0], pts[k][1], w, h, zfudge);
    }
}

module lid_snake_groove() {
    snake_path(lid_groove_w, lid_groove_d + 0.2, 0, -0.1);
}

module lid_lightening_pocket() {
    w = lid_pocket_track_w;
    h = lid_h - lid_top_skin + 0.1;
    offset = lid_groove_w / 2 + lid_pocket_gap_from_groove + w / 2;

    // Follow the same snake inward, removing material while preserving ribs.
    translate([0, 0, -0.1]) {
        scale_x = (box_w - 2 * offset) / box_w;
        scale_y = (box_d - 2 * offset) / box_d;
        translate([offset, offset, 0])
            scale([scale_x, scale_y, 1])
                snake_path(w, h, 0);
    }
}

module lid_center_cutout() {
    h = lid_h - lid_top_skin + 0.1;
    inset = snake_inner_center() + lid_groove_w / 2 + lid_pocket_gap_from_groove + lid_center_cut_margin;

    translate([inset, inset, -0.1])
        rounded_box(
            [box_w - 2 * inset, box_d - 2 * inset, h],
            max(0.1, corner_r - inset)
        );
}

module box_snake_corner_projection(w, h, z) {
    pts = snake_points();
    for (k = [0 : len(pts) - 1])
        translate([pts[k][0], pts[k][1], z])
            cylinder(d = w, h = h);
}

module old_lid_snake_groove_unused() {
    o = lid_groove_edge_offset + lid_groove_w / 2;
    i = lid_m4_hole_offset + lid_m4_hole_d / 2 + 2.0;
    z_h = lid_groove_d;

    pts = [
        [i, o], [box_w - i, o],
        [box_w - i, i], [box_w - o, i], [box_w - o, box_d - i], [box_w - i, box_d - i],
        [box_w - i, box_d - o], [i, box_d - o],
        [i, box_d - i], [o, box_d - i], [o, i], [i, i],
        [i, o]
    ];

    for (k = [0 : len(pts) - 2])
        groove_segment(pts[k][0], pts[k][1], pts[k + 1][0], pts[k + 1][1], lid_groove_w, z_h);

    for (k = [0 : len(pts) - 1])
        groove_node(pts[k][0], pts[k][1], lid_groove_w, z_h);
}

module lid_gasket_lip() {
    outer_inset = gasket_inset;
    inner_inset = gasket_inset + lid_gasket_wall_w + gasket_gap;

    // Outer skirt.
    translate([outer_inset, outer_inset, -lid_gasket_wall_h])
        perimeter_rect_frame(
            box_w - 2 * outer_inset,
            box_d - 2 * outer_inset,
            lid_gasket_wall_w,
            lid_gasket_wall_h
        );

    // Wide inner plug: this is the "toppa" in section, not a thin tooth.
    translate([inner_inset, inner_inset, -lid_gasket_wall_h])
        perimeter_rect_frame(
            box_w - 2 * inner_inset,
            box_d - 2 * inner_inset,
            lid_inner_plug_w,
            lid_gasket_wall_h
        );
}

module box_m4_insert_holes() {
    for (x = [lid_m4_hole_offset, box_w - lid_m4_hole_offset])
        for (y = [lid_m4_hole_offset, box_d - lid_m4_hole_offset])
            translate([x, y, wall - 0.2])
                cylinder(d = box_m4_insert_pilot_d, h = box_h - wall + 0.4);
}

module box_m4_bosses() {
    difference() {
        union() {
            // Solid pads below the lid M4 holes. They stay close to the corners;
            // the outer wall clips any part that would exceed the box footprint.
            for (x = [lid_m4_hole_offset, box_w - lid_m4_hole_offset])
                for (y = [lid_m4_hole_offset, box_d - lid_m4_hole_offset])
                    intersection() {
                        translate([x - box_m4_boss_w / 2, y - box_m4_boss_w / 2, wall])
                            cube([box_m4_boss_w, box_m4_boss_w, box_h - wall - box_m4_boss_top_clearance]);
                        translate([wall, wall, wall])
                            cube([box_w - 2 * wall, box_d - 2 * wall, box_h - wall]);
                    }
        }
        box_m4_insert_holes();
    }
}

module box_seal_male() {
    male_w = max(0.1, lid_groove_w - 2 * box_seal_clearance);

    difference() {
        union() {
            // Male lip, 1.5 mm above the top edge.
            snake_path(male_w, box_seal_rib_h, box_h);
        }
        box_m4_insert_holes();

    }
}

module vent_slots(count, slot_w, slot_h, gap) {
    total = count * slot_h + (count - 1) * gap;
    for (i = [0 : count - 1]) {
        translate([0, -total / 2 + i * (slot_h + gap), 0])
            cube([slot_w, slot_h, 20], center = true);
    }
}

function rpi_x(x) = rpi_rotation_deg == 180 ? rpi_board_x + rpi_w - x : rpi_board_x + x;
function rpi_y(y) = rpi_rotation_deg == 180 ? rpi_board_y + rpi_d - y : rpi_board_y + y;

module rpi_oriented_child() {
    translate([rpi_board_x, rpi_board_y, wall + rpi_standoff_h])
        if (rpi_rotation_deg == 180)
            translate([rpi_w, rpi_d, 0]) rotate([0, 0, 180]) children();
        else
            children();
}

// Visual-only placeholder. It is called with OpenSCAD's background modifier `%`,
// so it helps placement in preview but is not part of printable geometry.
module rpi5_simple_placeholder() {
    rpi_oriented_child() {
        color([0.05, 0.45, 0.18, 0.38])
            cube([rpi_w, rpi_d, rpi_pcb_t]);

        // Mounting holes, aligned to the printed standoffs.
        color([0, 0, 0, 0.55])
            for (x = [rpi_hole_from_left, rpi_hole_from_left + rpi_hole_dx])
                for (y = [rpi_hole_from_front, rpi_hole_from_front + rpi_hole_dy])
                    translate([x, y, rpi_pcb_t + 0.05])
                        cylinder(d = 2.8, h = 0.3);

        // 40-pin GPIO header along the long edge.
        color([0.06, 0.06, 0.06, 0.6])
            translate([7, rpi_d - 7, rpi_pcb_t])
                cube([rpi_gpio_w, rpi_gpio_d, rpi_gpio_h]);

        // USB-C power connector on the long side of the RPi5 board.
        color([0.75, 0.75, 0.75, 0.65])
            translate([6.75 - rpi_usbc_w / 2, -rpi_usbc_d + 0.2, rpi_pcb_t])
                cube([rpi_usbc_w, rpi_usbc_d, rpi_usbc_h]);

        // Dual micro-HDMI connectors on the same long side.
        color([0.35, 0.35, 0.38, 0.65]) {
            translate([22.5 - rpi_hdmi_w / 2, -rpi_hdmi_d + 0.2, rpi_pcb_t])
                cube([rpi_hdmi_w, rpi_hdmi_d, rpi_hdmi_h]);
            translate([35.85 - rpi_hdmi_w / 2, -rpi_hdmi_d + 0.2, rpi_pcb_t])
                cube([rpi_hdmi_w, rpi_hdmi_d, rpi_hdmi_h]);
        }

        // Two MIPI FFC camera/display connectors from SBC Model Framework rpi5 data.
        color([0.92, 0.86, 0.65, 0.7]) {
            translate([47 - rpi_mipi_d / 2, 1, rpi_pcb_t])
                cube([rpi_mipi_d, rpi_mipi_w, rpi_mipi_h]);
            translate([53.25 - rpi_mipi_d / 2, 1, rpi_pcb_t])
                cube([rpi_mipi_d, rpi_mipi_w, rpi_mipi_h]);
        }
    }
}

module rpi5_framework_placeholder() {
    rpi_oriented_child()
        sbc("rpi5", "disable", 0, "default", "default", false);
}

module rpi5_placeholder() {
    if (rpi_placeholder == "framework")
        rpi5_framework_placeholder();
    else if (rpi_placeholder == "simple")
        rpi5_simple_placeholder();
}

// ---------------- Base ----------------
module base_shell() {
    difference() {
        rounded_box([box_w, box_d, box_h], corner_r);

        translate([wall, wall, wall])
            rounded_box(
                [box_w - 2 * wall, box_d - 2 * wall, box_h],
                max(0.1, corner_r - wall)
            );

        // Camera square opening on the short side, shifted toward the RPi FFC connectors.
        translate([
            -1,
            cam_side_y - cam_square_opening / 2,
            box_h * 0.58 + cam_opening_z_offset - cam_square_opening / 2
        ])
            cube([wall + box_seal_rib_h + 2, cam_square_opening, cam_square_opening]);

        // Shallow exterior pocket for a 20 x 20 x 1 mm optical window.
        translate([
            -0.1,
            cam_side_y - cam_window_pocket_size / 2,
            box_h * 0.58 + cam_opening_z_offset - cam_window_pocket_size / 2
        ])
            cube([
                cam_window_pocket_depth + 0.1,
                cam_window_pocket_size,
                cam_window_pocket_size
            ]);

    }
}

module camera_mount() {
    mount_z = box_h * 0.58;
    mount_x = wall - 0.15;
    plate_w = cam_screw_gap_x + 2 * cam_plate_side_margin;
    plate_h = mount_z + cam_screw_gap_z / 2 + cam_plate_top_margin - wall;
    plate_z = wall;
    hole_center_z = mount_z - plate_z;

    // Thin inner plate merged into the short side wall and resting on the floor.
    translate([mount_x, cam_side_y - plate_w / 2, plate_z])
        difference() {
            cube([cam_plate_t, plate_w, plate_h]);

            // Lens clearance, aligned with the square side opening.
            translate([
                -0.2,
                plate_w / 2 - cam_square_opening / 2,
                hole_center_z + cam_opening_z_offset - cam_square_opening / 2
            ])
                cube([cam_plate_t + 0.4, cam_square_opening, cam_square_opening]);

            // Camera board fixing holes, approximate Camera Module 3 pattern.
            for (yoff = [-cam_screw_gap_x / 2, cam_screw_gap_x / 2])
                for (zoff = [-cam_screw_gap_z / 2, cam_screw_gap_z / 2])
                    translate([-0.2, plate_w / 2 + yoff, hole_center_z + zoff])
                        rotate([0, 90, 0])
                            cylinder(d = cam_screw_d, h = cam_plate_t + 0.4);
        }
}

module base() {
    union() {
        base_shell();

        // RPi standoffs, centered but shifted back for cable room near front camera.
        for (x = [rpi_hole_from_left, rpi_hole_from_left + rpi_hole_dx])
            for (y = [rpi_hole_from_front, rpi_hole_from_front + rpi_hole_dy])
                rpi_standoff(rpi_x(x), rpi_y(y));

        camera_mount();
        box_m4_bosses();
        color("red") box_seal_male();

        if (rpi_placeholder != "off")
            %rpi5_placeholder();
    }
}

// ---------------- Lid ----------------
module lid() {
    difference() {
        rounded_box([box_w, box_d, lid_h], corner_r);

        // 2 x 2 mm underside groove. It snakes inward near the M4 holes.
        lid_snake_groove();

        // Material saving pocket: leaves 1.5 mm of top skin.
        lid_lightening_pocket();

        // Remove the large center area while preserving the snake frame.
        lid_center_cutout();

        // Four M4 clearance holes for fastening the lid to the box.
        for (x = [lid_m4_hole_offset, box_w - lid_m4_hole_offset])
            for (y = [lid_m4_hole_offset, box_d - lid_m4_hole_offset])
                translate([x, y, -0.1]) {
                    cylinder(d = lid_m4_hole_d, h = lid_h + 0.2);
                }
    }
}

module assembly() {
    color("gainsboro") base();
    translate([0, 0, box_h + 34])
        color("lightsteelblue") lid();
}

if (part == "box" || part == "base") {
    base();
} else if (part == "lid") {
    translate([0, box_d, lid_h])
        rotate([180, 0, 0])
            lid();
} else {
    assembly();
}
