/*
  AgriApp field enclosure - first prototype.

  Units: millimeters.
  Render one part at a time by changing `part`.

  part = "box";
  part = "lid";
  part = "assembly";

  `base` is still accepted as an alias for `box`.
*/

/* [View] */
part = "assembly"; // [assembly, box, lid]

// ---------------- Parameters ----------------
wall = 2.4;
clearance = 0.35;

box_w = 118;
box_d = 92;
box_h = 42;
corner_r = 5;

lid_h = 7;
lid_lip_h = 4;
lid_lip_inset = wall + 0.35;
screw_d = 3.2;
screw_post_d = 8.5;

// Raspberry Pi 5 / 4 family approximate mounting hole pattern.
rpi_w = 85;
rpi_d = 56;
rpi_hole_dx = 58;
rpi_hole_dy = 49;
rpi_standoff_h = 6;
rpi_standoff_d = 6.5;
rpi_screw_d = 2.8;

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
cam_plate_t = 5;
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

module vent_slots(count, slot_w, slot_h, gap) {
    total = count * slot_h + (count - 1) * gap;
    for (i = [0 : count - 1]) {
        translate([0, -total / 2 + i * (slot_h + gap), 0])
            cube([slot_w, slot_h, 20], center = true);
    }
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

        // Front camera elliptical opening, kept 4.5 mm away from left/right screw centers.
        translate([box_w / 2, -0.1, box_h * 0.58 + cam_opening_z_offset])
            rotate([-90, 0, 0])
                scale([cam_opening_w / cam_opening_h, 1, 1])
                    cylinder(d = cam_opening_h, h = wall + 1);

        // USB/power cable opening, intentionally oversized for first prototype.
        translate([box_w - 24, box_d - wall - 0.1, 16])
            cube([18, wall + 1, 12]);
    }
}

module camera_mount() {
    mount_z = box_h * 0.58;
    mount_y = wall - 0.15;
    plate_w = cam_screw_gap_x + 2 * cam_plate_side_margin;
    plate_h = mount_z + cam_screw_gap_z / 2 + cam_plate_top_margin - wall;
    plate_z = wall;
    hole_center_z = mount_z - plate_z;

    // Thin inner plate merged into the front wall and resting on the floor.
    translate([(box_w - plate_w) / 2, mount_y, plate_z])
        difference() {
            cube([plate_w, cam_plate_t, plate_h]);

            // Lens clearance, aligned with the elliptical front opening.
            translate([plate_w / 2, -0.2, hole_center_z + cam_opening_z_offset])
                rotate([-90, 0, 0])
                    scale([cam_opening_w / cam_opening_h, 1, 1])
                        cylinder(d = cam_opening_h, h = cam_plate_t + 0.4);

            // Camera board fixing holes, approximate Camera Module 3 pattern.
            for (xoff = [-cam_screw_gap_x / 2, cam_screw_gap_x / 2])
                for (zoff = [-cam_screw_gap_z / 2, cam_screw_gap_z / 2])
                    translate([plate_w / 2 + xoff, -0.2, hole_center_z + zoff])
                        rotate([-90, 0, 0])
                            cylinder(d = cam_screw_d, h = cam_plate_t + 0.4);
        }
}

module base() {
    union() {
        base_shell();

        // Corner posts for lid screws.
        for (x = [12, box_w - 12])
            for (y = [12, box_d - 12])
                screw_post(x, y, box_h - wall);

        // RPi standoffs, centered but shifted back for cable room near front camera.
        rpi_cx = box_w / 2;
        rpi_cy = box_d / 2 + 8;
        for (xoff = [-rpi_hole_dx / 2, rpi_hole_dx / 2])
            for (yoff = [-rpi_hole_dy / 2, rpi_hole_dy / 2])
                rpi_standoff(rpi_cx + xoff, rpi_cy + yoff);

        camera_mount();
    }
}

// ---------------- Lid ----------------
module lid() {
    difference() {
        union() {
            rounded_box([box_w, box_d, lid_h], corner_r);

            // Inner lip.
            translate([lid_lip_inset, lid_lip_inset, -lid_lip_h])
                difference() {
                    rounded_box(
                        [box_w - 2 * lid_lip_inset, box_d - 2 * lid_lip_inset, lid_lip_h],
                        max(0.1, corner_r - lid_lip_inset)
                    );
                    translate([wall, wall, -0.1])
                        rounded_box(
                            [
                                box_w - 2 * lid_lip_inset - 2 * wall,
                                box_d - 2 * lid_lip_inset - 2 * wall,
                                lid_lip_h + 0.2
                            ],
                            max(0.1, corner_r - lid_lip_inset - wall)
                        );
                }

            // GPS placement marker/recess on top.
            translate([(box_w - gps_zone_w) / 2, box_d - gps_zone_d - 10, lid_h - 0.15])
                cube([gps_zone_w, gps_zone_d, gps_zone_h]);
        }

        // Lid screw holes.
        for (x = [12, box_w - 12])
            for (y = [12, box_d - 12])
                translate([x, y, -lid_lip_h - 0.2])
                    cylinder(d = screw_d, h = lid_h + lid_lip_h + gps_zone_h + 0.4);
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
    lid();
} else {
    assembly();
}
