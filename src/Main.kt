import javax.swing.*
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import kotlin.math.*
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.math.tanh


//see if cars can do it without speed cap?
//add australia
//add player control back and that stuff
//minimize steering jitter?



// Constants for Physics
const val ACCELERATION = 0.2
const val FRICTION = 0.96
const val STEER_SPEED = 0.05
const val MAX_SPEED = 8.0
const val BRAKE_FORCE = ACCELERATION * 2


var speedFactor = false
var simSpeed = 1

var trainingWheelsActive = false
val TRAINING_MAX_SPEED = 2.0 // Much slower and manageable
val REAL_MAX_SPEED = 5.0     // Your original high speed
var aiReplayFrame = 0
var playerReplayFrame = 0
var isViewingReplay = false
var playerMoving = true
var mode = "Player Control"
var oldMode = ""


object TrackFactory {
    val lusailPoints = listOf(
    Point2D.Double(150.0, 150.0),  // Start/Finish
    Point2D.Double(850.0, 150.0),  // Main Straight
    Point2D.Double(920.0, 180.0),  // T1
    Point2D.Double(940.0, 250.0),  // T2
    Point2D.Double(900.0, 320.0),  // T3
    Point2D.Double(950.0, 450.0),  // T4
    Point2D.Double(850.0, 520.0),  // T5
    Point2D.Double(750.0, 500.0),  // T6 (Hairpin approach)
    Point2D.Double(600.0, 650.0),  // T6 Apex
    Point2D.Double(450.0, 550.0),  // T7
    Point2D.Double(400.0, 600.0),  // T8
    Point2D.Double(300.0, 580.0),  // T9
    Point2D.Double(200.0, 650.0),  // T10
    Point2D.Double(100.0, 550.0),  // T11
    Point2D.Double(150.0, 400.0),  // T12
    Point2D.Double(100.0, 350.0),  // T13
    Point2D.Double(50.0, 380.0),   // T14
    Point2D.Double(30.0, 300.0),   // T15
    Point2D.Double(80.0, 180.0),   // T16
    //Point2D.Double(150.0, 150.0)   // Back to Start
)
    val bahrainPoints = listOf(
        Point2D.Double(80.0, 672.0),   // Start/Finish
        Point2D.Double(128.0, 600.0),   // Turn 1 approach
        Point2D.Double(108.0, 539.0),   // Turn 1 Apex

        Point2D.Double(190.0, 75.0),    // Long Straight to T4 (was 81)
        Point2D.Double(262.0, 65.0),    // T4 Exit (was 74)

        Point2D.Double(567.0, 360.0),   // Long sweep
        Point2D.Double(570.0, 425.0),   // T12 Apex

        Point2D.Double(260.0, 453.0),   // Back Straight
        Point2D.Double(265.0, 550.0),   // Final Sector entrance
        Point2D.Double(828.0, 534.0),   // Long Bottom Straight
        Point2D.Double(851.0, 467.0),   // T13
        Point2D.Double(803.0, 403.0),   // Turning back
        Point2D.Double(696.0, 393.0),   // Midfield
        Point2D.Double(652.0, 253.0),   // Technical section
        Point2D.Double(767.0, 120.0),   // High speed right
        Point2D.Double(1125.0, 664.0),  // Far right hairpin
        Point2D.Double(1045.0, 687.0),  // Return sweep
        Point2D.Double(150.0, 691.0)    // Final approach to Finish
    )
    private fun generate(name: String, points: List<Point2D>, startAngle: Double): RacingTrack {
        val trackWidth = 100.0 // Keeping it wider as we discussed
        val innerPoints = mutableListOf<Point2D>()
        val outerPoints = mutableListOf<Point2D>()

        for (i in points.indices) {
            val p1 = points[i]
            val pPrev = points[if (i == 0) points.size - 1 else i - 1]
            val pNext = points[(i + 1) % points.size]

            // Vector from previous to current
            val v1x = p1.x - pPrev.x
            val v1y = p1.y - pPrev.y
            val v1Len = sqrt(v1x * v1x + v1y * v1y)

            // Vector from current to next
            val v2x = pNext.x - p1.x
            val v2y = pNext.y - p1.y
            val v2Len = sqrt(v2x * v2x + v2y * v2y)

            // Unit normals
            val n1x = -v1y / v1Len
            val n1y = v1x / v1Len
            val n2x = -v2y / v2Len
            val n2y = v2x / v2Len

            // Average normal (miter vector)
            var mx = n1x + n2x
            var my = n1y + n2y
            val mLen = sqrt(mx * mx + my * my)
            mx /= mLen
            my /= mLen

            // Scale the miter to prevent "spiking" in tight turns
            val dot = n1x * n2x + n1y * n2y
            val miterLimit = 3.0
            val scale = ((trackWidth / 2.0) / sqrt((1.0 + dot) / 2.0)).coerceAtMost(trackWidth * miterLimit)

            innerPoints.add(Point2D.Double(p1.x + mx * scale, p1.y + my * scale))
            outerPoints.add(Point2D.Double(p1.x - mx * scale, p1.y - my * scale))
        }

        val walls = mutableListOf<Line2D>()
        val checkpoints = mutableListOf<Line2D>()
        for (i in 0 until innerPoints.size) {
            val next = (i + 1) % innerPoints.size
            walls.add(Line2D.Double(innerPoints[i], innerPoints[next]))
            walls.add(Line2D.Double(outerPoints[i], outerPoints[next]))
            checkpoints.add(Line2D.Double(innerPoints[i], outerPoints[i]))
        }

        return RacingTrack(name, walls, checkpoints, points[0].x, points[0].y, startAngle)
    }

    fun createQatar() = generate("Lusail", lusailPoints, 0.0)
    fun createBahrain() = generate("Sakhir", bahrainPoints, -1.2)
}
var furthestCheckpoint = 0

class RacingTrack(
    val name: String,
    val walls: List<Line2D>,
    val checkpoints: List<Line2D>,
    val startX: Double,
    val startY: Double,
    val startAngle: Double
) {

    var aiFastestTime: Double = 999.9
    var playerFastestTime: Double = 999.9
    var aiBestReplay = mutableListOf<Triple<Double, Double, Double>>()
    var playerBestReplay = mutableListOf<Triple<Double, Double, Double>>()

    var bestLapReplay = mutableListOf<Triple<Double, Double, Double>>()
    fun saveReplay(history: List<Triple<Double, Double, Double>>) {
        // Use .toList() to create a static copy of the winner's path
        bestLapReplay = history.toList().toMutableList()
    }


    // Draw the walls and checkpoints
    fun draw(g2d: Graphics2D) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Draw the "Road" (Gray fill between walls)
        // This is optional but makes it look much better

        g2d.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2d.color = Color.WHITE
        for (wall in walls) {
            g2d.draw(wall)
        }

        // Draw checkpoints in a subtle color
        for (cp in checkpoints) {
            g2d.color = Color(0, 255, 255, 50)
            if (checkpoints.indexOf(cp) == playerCar.nextCheckpointIndex && playerMoving) {
                g2d.color = Color(50,255,50,50)
                if (checkpoints.indexOf(cp) == checkpoints.size - 1) g2d.color = Color(255,50,50,50)
            }

            g2d.draw(cp)
        }
    }

    // Check if a point (x, y) hits any wall
    fun checkCollision(x: Double, y: Double): Line2D? {
        // We use a small radius for the car collision
        val carPoint = Point2D.Double(x, y)
        for (wall in walls) {
            if (wall.ptSegDist(carPoint) < 5.0) { // 5.0 is the car's hit-box radius
                return wall
            }
        }
        return null
    }

}

private val trackLibrary = listOf(TrackFactory.createQatar(), TrackFactory.createBahrain())
private var currentTrackIndex = 0
var track = trackLibrary[currentTrackIndex]

// Make sure your RaceCar class is 'open' so it can be inherited from
// open class RaceCar(...)

val playerCar = PlayerCar(0.0,0.0,NeuralNetwork(0,0,0))
class PlayerCar(x: Double, y: Double, brain: NeuralNetwork) : RaceCar(x, y, brain) {

    init {
        this.resetPlayer(this)

    }
    fun handleInput(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
        if (isDead) return

        // Manual control of the inherited variables
        throttle = if (up) 1.0 else if (down) -1.0 else 0.0
        steering = if (left) -0.1 else if (right) 0.1 else 0.0

        this.update(left, right, throttle)
    }


}


fun Color.withAlpha(a: Int) = Color(this.red, this.green, this.blue, a)

open class RaceCar(var x: Double, var y: Double, val brain: NeuralNetwork = NeuralNetwork(10, 20, 2)) {
    var angle: Double = 0.0 // In Radians
    var speed: Double = 0.0
    var fitness: Double = 0.0
    var nextCheckpointIndex: Int = 0
    var isDead = false
    var framesSinceCheckpoint = 0
    var wrongWayFrames = 0

    var frameCount = 0             // How many frames this car has been alive
    var distToNextCheckpoint = 0.0 // Distance to its specific target
    var prevDistToGoal = Double.MAX_VALUE
    var lastRecordCheckpoint = 0

    val pathHistory = mutableListOf<Triple<Double, Double, Double>>() // X, Y, Angle

    var throttle: Double = 0.0
    var steering: Double = 0.0



    open fun update(left: Boolean, right: Boolean, throttle: Double) {
        if (throttle > 0) {
            speed += ACCELERATION * throttle
        } else {
            // If throttle is negative, apply "Brakes" (much stronger than friction)
            speed += BRAKE_FORCE * throttle // throttle is negative, so speed decreases
        }

        val turnModifier = 1.0 - (speed / MAX_SPEED * 0.7)
        if (left) angle -= STEER_SPEED * turnModifier
        if (right) angle += STEER_SPEED * turnModifier

        speed *= FRICTION

        x += cos(angle) * (speed)
        y += sin(angle) * (speed)

        if (speed < 0) speed = 0.0 // Don't let them drive backwards yet

        val currentCap = if (trainingWheelsActive && !playerMoving) TRAINING_MAX_SPEED else 999.0

        // After applying acceleration/friction:
        if (speed > currentCap) {
            speed = currentCap
        }

        this.updatePath(this)

    }

    open fun updatePath(car: RaceCar) {
        if (!car.isDead && frameCount % 3 == 0) {
            pathHistory.add(Triple(this.x, this.y, this.angle))
        }
    }

    fun think(walls: List<Line2D>) {
        val inputs = DoubleArray(10)
        val sensorAngles = listOf(-PI/2, PI/6, -PI/4, 0.0, PI/4, PI/6, PI/2)
        for (i in sensorAngles.indices) {
            inputs[i] = getSensorDistance(walls, sensorAngles[i]) / 500.0
        }
        inputs[7] = (speed / MAX_SPEED).coerceIn(0.0, 1.0)
        val currentCheckpoint = track.checkpoints[nextCheckpointIndex]
        val futureCheckpoint = track.checkpoints[(nextCheckpointIndex + 1) % track.checkpoints.size]
        val goalX = (currentCheckpoint.x1 + currentCheckpoint.x2) / 2.0
        val goalY = (currentCheckpoint.y1 + currentCheckpoint.y2) / 2.0
        val angleToGoal = atan2(goalY - y, goalX - x) - this.angle
        inputs[8] = (angleToGoal / PI).coerceIn(-1.0, 1.0)


        // Calculate the vector of the upcoming segment
        val turnSeverity = getAngleBetween(currentCheckpoint, futureCheckpoint)
        inputs[9] = turnSeverity // Normalized -1.0 (hard left) to 1.0 (hard right)

        val output = brain.feedForward(inputs)

        // AI controls: Adjust threshold as needed
        val throttle = output[0]
        val left = output[1] < -0.2
        val right = output[1] > 0.2

        // Call the update logic here or store these for the GamePanel loop
        this.update(left, right, throttle)
    }

    val carWidth = 30
    val carHeight = 15

    fun draw(g: Graphics2D) {


        val oldTransform = g.transform
        g.translate(x, y)
        g.rotate(angle)

        // Draw Car Body
        g.color = Color.RED
        g.fillRect(-carWidth / 2, -carHeight / 2, carWidth, carHeight)

        // Draw "Front" indicator
        g.color = Color.BLACK
        g.fillRect(carWidth / 4, -carHeight / 2, 5, carHeight)

        g.transform = oldTransform

    }

    fun getAngleBetween(line1: Line2D, line2: Line2D): Double {
        // Calculate the angle of the first checkpoint
        val angle1 = atan2(line1.y2 - line1.y1, line1.x2 - line1.x1)

        // Calculate the angle of the second checkpoint
        val angle2 = atan2(line2.y2 - line2.y1, line2.x2 - line2.x1)

        // Return the difference, normalized between -1.0 and 1.0
        var diff = angle2 - angle1

        // Normalize to handle the -PI to PI wrap-around
        while (diff < -PI) diff += 2 * PI
        while (diff > PI) diff -= 2 * PI

        return diff / PI
    }

    fun resetPlayer(player: PlayerCar) {
        player.x = track.startX
        player.y = track.startY
        player.angle = track.startAngle
        player.speed = 0.0
        player.isDead = false
        player.nextCheckpointIndex = 0
        player.frameCount = 0
        player.pathHistory.clear() // CRITICAL: Start the recording over
    }

    fun drawSensors(g: Graphics2D, walls: List<Line2D>) {
        val angles = listOf(-PI/2, -PI/6, -PI/4, 0.0, PI/4, PI/6, PI/2) // 5 directions
        g.color = Color.CYAN

        for (a in angles) {
            val dist = getSensorDistance(walls, a)
            val endX = x + cos(angle + a) * dist
            val endY = y + sin(angle + a) * dist
            g.draw(Line2D.Double(x, y, endX, endY))
        }
    }

    fun getBounds(): Rectangle2D.Double {
        // We subtract half the width/height so the (x,y) is the CENTER of the car
        return Rectangle2D.Double(
            x - carWidth / 2,
            y - carHeight / 2,
            carWidth.toDouble(),
            carHeight.toDouble()
        )
    }

    fun getSensorDistance(wallList: List<Line2D>, sensorAngleOffset: Double): Double {
        val maxRange = 500.0 // How far the car can "see"
        val rayAngle = this.angle + sensorAngleOffset

        val rayEndX = x + cos(rayAngle) * maxRange
        val rayEndY = y + sin(rayAngle) * maxRange
        val ray = Line2D.Double(x, y, rayEndX, rayEndY)

        var minDistance = maxRange

        for (wall in wallList) {
            if (ray.intersectsLine(wall)) {
                val intersection = getIntersection(ray, wall)
                if (intersection != null) {
                    val dist = Point2D.distance(x, y, intersection.x, intersection.y)
                    if (dist < minDistance) minDistance = dist
                }
            }
        }
        return minDistance
    }

    // Helper to find the exact (x, y) of intersection
    private fun getIntersection(line1: Line2D, line2: Line2D): Point2D.Double? {
        val x1 = line1.x1; val y1 = line1.y1
        val x2 = line1.x2; val y2 = line1.y2
        val x3 = line2.x1; val y3 = line2.y1
        val x4 = line2.x2; val y4 = line2.y2

        val det = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (det == 0.0) return null // Parallel

        val px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / det
        val py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / det
        return Point2D.Double(px, py)
    }
}



class GamePanel : JPanel(), KeyListener {

    private val popManager = GenerationManager(50, track.startX, track.startY, track.startAngle)
    private val keys = mutableSetOf<Int>()


    private var hasStarted = false
    private var isPaused = false // New flag for the reset pause

    private var frameCount = 0
    private val maxFrames = 60 * 40 //seconds at 60 fps


    init {
        isFocusable = true
        addKeyListener(this)

        Timer(0) {

            if (!playerMoving) {
                repeat(simSpeed) {
                    frameCount++

                    // 1. Check for Generation Reset
                    val allDead = popManager.cars.all { it.isDead }
                    if (frameCount >= maxFrames || allDead) {
                        popManager.nextGeneration()
                        frameCount = 0 // Reset our counter!
                        return@repeat
                    }

                    // 2. Update all AI cars
                    for (aiCar in popManager.cars) {
                        if (!aiCar.isDead) {
                            aiCar.framesSinceCheckpoint++
                            aiCar.frameCount++

                            // Every 600 frames (10 second), check if they've actually made progress
                            if (frameCount % 600 == 0) {
                                //if (aiCar.lastRecordCheckpoint == aiCar.nextCheckpointIndex) aiCar.isDead = true
                                aiCar.lastRecordCheckpoint = aiCar.nextCheckpointIndex
                            }

                            // 2. Update the distance to the next checkpoint
                            val goal = track.checkpoints[aiCar.nextCheckpointIndex]
                            // Using the center of the checkpoint line for a more accurate distance
                            val goalMidX = (goal.x1 + goal.x2) / 2.0
                            val goalMidY = (goal.y1 + goal.y2) / 2.0

                            val dx = goalMidX - aiCar.x
                            val dy = goalMidY - aiCar.y
                            aiCar.distToNextCheckpoint = sqrt(dx * dx + dy * dy)


                            val checkpoint = track.checkpoints[aiCar.nextCheckpointIndex]
                            val trackDirX = checkpoint.x2 - checkpoint.x1
                            val trackDirY = checkpoint.y2 - checkpoint.y1

                            val carDirX = cos(aiCar.angle)
                            val carDirY = sin(aiCar.angle)

                            // Dot product tells us if they are facing the same way
                            val directionMatch = (carDirX * trackDirX + carDirY * trackDirY)

                            if (directionMatch < -0.2) { // Facing mostly backwards
                                aiCar.wrongWayFrames++
                            } else {
                                aiCar.wrongWayFrames = 0
                            }


                            // AI looks at sensors and decides what to do
                            //gets inputs, processes them, does actions, moves the car
                            aiCar.think(track.walls)

                            // 1. Calculate a speed multiplier (e.g., speed squared)
                            // This makes 10 units of speed worth 100, but 20 units worth 400.
                            //val speedFitness = (aiCar.speed * aiCar.speed) * 0.075
                            aiCar.fitness += (aiCar.speed * 0.1)
                            aiCar.fitness += frameCount / 60.0 / 50.0


                            // 2. Add a "Time Penalty"
                            // Subtract a small amount every frame. This rewards cars that
                            // reach checkpoints SOONER rather than later.
                            if (speedFactor) aiCar.fitness -= 0.25 * (aiCar.nextCheckpointIndex)


                            // Run sub-stepping physics for each AI car

                            //aiCar.think(track.walls)

                            checkCollisionsAndFitness(aiCar)
                            if (frameCount == 0) {
                                // 3. Exit the current simulation loops to start the new gen fresh
                                return@repeat
                            }

                        }
                    }

                }
            }
            else {
                frameCount++
                playerCar.handleInput(upPressed, downPressed, leftPressed, rightPressed)
                playerCar.update(leftPressed, rightPressed, playerCar.throttle)
                playerCar.updatePath(playerCar)
                checkPlayerLap(playerCar)
            }

            repaint()
        }.start()
    }


    fun checkPlayerLap(player: PlayerCar) {
        val bounds = player.getBounds()
        // 1. Check Walls
        for (wall in track.walls) {
            if (bounds.intersectsLine(wall)) {
                player.isDead = true
                player.resetPlayer(player)
                frameCount = 0
                return // Exit early since we already hit something
            }
        }

        // 2. Check Checkpoints
        val currentGoal = track.checkpoints[player.nextCheckpointIndex]
        if (bounds.intersectsLine(currentGoal)) {
            player.nextCheckpointIndex++

            // Handle finish line
            if (player.nextCheckpointIndex >= track.checkpoints.size) {
                val finishTime = frameCount / 60.0

                println(track.playerFastestTime)
                // 3. Compare to personal best
                if (finishTime < track.playerFastestTime) {
                    track.playerFastestTime = finishTime
                    // Save the path to the PLAYER replay slot
                    track.playerBestReplay = player.pathHistory.toList().toMutableList()
                }

                // 4. Reset player for next attempt
                player.isDead = true
                player.resetPlayer(player)
                frameCount = 0
                println(track.playerFastestTime)
            }
        }
    }

    private fun checkCollisionsAndFitness(aiCar: RaceCar) {
        // Now calling the function inside the RacingTrack class
        val hitWall = track.checkCollision(aiCar.x, aiCar.y)

        if (hitWall != null) {
            aiCar.isDead = true

            val impactPenalty = aiCar.speed * 2.0
            aiCar.fitness -= impactPenalty

            aiCar.fitness *= 0.5
            return
        }

        // Checkpoints check
        var goal = track.checkpoints[aiCar.nextCheckpointIndex]
        if (goal.ptSegDist(aiCar.x, aiCar.y) < 5.0) { // Adjusted distance for Lusail gates (changeable)
            aiCar.nextCheckpointIndex++
            if (aiCar.nextCheckpointIndex > furthestCheckpoint) furthestCheckpoint = aiCar.nextCheckpointIndex
            aiCar.framesSinceCheckpoint = 0
            aiCar.fitness += 150.0 + (aiCar.speed * 5.0) // Big reward for progress

            if (aiCar.nextCheckpointIndex >= track.checkpoints.size) {
                // Victory logic...
                aiCar.nextCheckpointIndex = 0 // Lap completed!
                aiCar.fitness += 300.0 // Bonus for finishing

                //making it end as soon as one car gets over
                speedFactor = true
                trainingWheelsActive = false
                val finishTimeSeconds = frameCount / 60.0
                if (finishTimeSeconds < track.aiFastestTime) {
                    track.aiFastestTime = finishTimeSeconds
                    aiCar.fitness *= 1.3
                    track.saveReplay(aiCar.pathHistory)
                }

                frameCount = 0
                popManager.nextGeneration()
            }
        }

        goal = track.checkpoints[track.checkpoints.size-1]
        if (goal.ptSegDist(aiCar.x, aiCar.y) < 5.0) {
            aiCar.isDead = true
        }
    }

    fun drawGhost(g2d: Graphics2D, replayData: List<Triple<Double, Double, Double>>, color: Color, label: String, replayFrame: Int) {
        if (replayData.isEmpty()) return

        // Ensure we don't go out of bounds if one replay is longer than the other
        val currentIdx = if (replayFrame >= replayData.size) {
            replayData.size - 1
        } else {
            replayFrame
        }

        val (gx, gy, gAngle) = replayData[currentIdx]

        val transform = g2d.transform
        g2d.translate(gx, gy)
        g2d.rotate(gAngle)

        // Draw the car body
        g2d.color = color
        g2d.fillRect(-30 / 2, -15 / 2, 30, 15)

        // Direction indicator
        g2d.color = Color.BLACK
        g2d.fillRect(30 / 4, -15 / 2, 5, 15)

        g2d.transform = transform

        // Label (Optional: draws "AI" or "Player" over the car)
        g2d.color = Color.WHITE
        g2d.drawString(label, gx.toInt() - 10, gy.toInt() - 15)
    }


    override fun paintComponent(g: Graphics) {
        val g2d = g as Graphics2D
        super.paintComponent(g)

        // RESET: Ensure we start 100% opaque every single frame
        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f)


        // 1. Background
        g2d.color = Color.DARK_GRAY
        g2d.fillRect(0, 0, width, height)

        g2d.color = Color(255, 255, 255, 30) // Faint white
        for (i in 0..2000 step 50) {
            g2d.drawLine(i, 0, i, 1000) // Vertical
            g2d.drawLine(0, i, 2000, i) // Horizontal
        }

        // 2. Draw the Track (Walls and Checkpoints)
        track.draw(g2d) //-1 for no global checkpoint highlight

        // 3. Draw the AI "Eyes" (Sensors)
        // We pass the walls from the track so the car knows what to "see"
        //popManager.cars[0].drawSensors(g2d, track.walls)

        // 4. Draw the Car
        if (!isViewingReplay && !playerMoving) {
            val currentLeader = popManager.cars.filter { !it.isDead }.maxByOrNull { it.fitness }

            for (car in popManager.cars) {
                if (car.isDead) continue // Optional: don't draw dead cars to reduce clutter


                // Use the transparency trick we discussed earlier
                val alpha = if (car == popManager.cars[0]) 1.0f else 0.4f
                g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                car.draw(g2d)
            }
        }

        if (playerMoving) {
            playerCar.draw(g2d)
        }


        if (isViewingReplay) {
            drawGhost(g2d, track.bestLapReplay, Color(0, 255, 255, 180), "AI", aiReplayFrame)
            drawGhost(g2d, track.playerBestReplay, Color(255, 0, 255, 180), "Player", playerReplayFrame)
            // Only increment the frame counter once per loop
            playerReplayFrame += 3
            aiReplayFrame = playerReplayFrame / 9
            frameCount++
        }

        // UI Overlay
        //draw stats and stuff
        drawAIStats(g2d)
    }


    // This function handles what happens during the impact
    private fun handleCollision(car: RaceCar, wall: Line2D) {
        val dx = wall.x2 - wall.x1
        val dy = wall.y2 - wall.y1
        val wallAngle = atan2(dy, dx)

        // 1. Calculate the impact angle
        val angleDiff = wallAngle - car.angle
        val normalizedDiff = atan2(sin(angleDiff), cos(angleDiff))

        // 2. Head-on Collision Check:
        // If the angle of impact is steep (near 90 degrees), just bounce back
        // and don't try to rotate into the wall.
        if (abs(normalizedDiff) in (PI/4)..(3*PI/4)) {
            car.speed *= -0.4 // Strong bounce back
        } else {
            // Scraping collision: Align slightly with wall
            car.speed *= 0.8
            car.angle += normalizedDiff * 0.1 // Reduced rotation multiplier
        }

        // 3. The "Emergency Eject":
        // Use the wall normal to move the car out of the wall immediately
        val normalAngle = wallAngle + PI / 2
        car.x += cos(normalAngle) * 3
        car.y += sin(normalAngle) * 3

        // Double check if we're still inside; if so, push the other way
        if (track.checkCollision(car.x, car.y) != null) {
            car.x -= cos(normalAngle) * 6
            car.y -= sin(normalAngle) * 6
        }
    }

    private fun drawAIStats(g2d: Graphics2D) {
        val uiBackground = Color(0, 0, 0, 150)

        // Save the composite that the cars might have left behind
        val carComposite = g2d.composite

        // 1. Force the UI to be opaque (so the Color's alpha works correctly)
        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f)

        // 2. Draw the Box with its OWN transparency
        g2d.color = Color(0, 0, 0, 125) // 125 is the "baked-in" transparency
        g2d.fillRect(width-240, 10, 235, 235-60)

        //other box
        g2d.fillRect(width-240, height-100,235,90)

        g2d.color = Color.BLACK
        g2d.font = Font("Arial", Font.BOLD, 25)
        if (mode == "AI Training") {
            g2d.drawString("MODE: $mode (${simSpeed}x Speed)", 15, 30) //changed to show mult speed
        }
        else g2d.drawString("MODE: $mode", 15, 30)

        g2d.color = Color.GREEN
        g2d.font = Font("Monospaced", Font.BOLD, 14)

        val elapsed = frameCount / 60.0
        //val maxSeconds = maxFrames / 60.0
        val bestInGen = popManager.cars.maxByOrNull { it.fitness }?.fitness ?: 0.0
        val aliveCount = popManager.cars.count { !it.isDead }

        // Formatting the fastest time
        val bestTimeDisplay = if (track.aiFastestTime == 999.9) "N/A" else "%.2f s".format(track.aiFastestTime)
        val playerBestTimeDisplay = if (track.playerFastestTime == 999.9) "N/A" else "%.2f s".format(track.playerFastestTime)

        g2d.drawString("GENERATION:  ${generationCount}", width-230, 40)
        g2d.drawString("ALIVE:       $aliveCount / ${popManager.populationSize}", width-230, 60)
        g2d.drawString("TRACK:       ${track.name}", width-230, 80)
        //100
        g2d.color = Color.WHITE
        g2d.drawString("TIME:        %.1f / %.1f s".format(elapsed, (maxFrames/60).toDouble()), width-230, 120)
        g2d.color = Color.CYAN
        g2d.drawString("AI RECORD:   $bestTimeDisplay", width-230, 140)
        g2d.color = Color.YELLOW
        g2d.drawString("YOUR RECORD: $playerBestTimeDisplay", width-230, 160)

        g2d.color = Color.WHITE
        g2d.drawString("ENTER: SWITCH TRACK", width-230,height-80)
        g2d.drawString("SPACE: TOGGLE MODE", width-230, height-60)
        g2d.drawString("01234: TRAINING SPEED",width-230,height-40)
        g2d.drawString("R:     TOGGLE REPLAY", width-230, height-20)

        g2d.composite = carComposite
        //todo: make neater

    }

    //independent
    //qatar 8.18
    //bahrain 18.25
    //together
    //qatar 8.52
    //bahrain 17.60



    // Key Listener Overrides
    var upPressed = false
    var downPressed = false
    var leftPressed = false
    var rightPressed = false

    override fun keyPressed(e: KeyEvent) {
        when (e.keyCode) {
            KeyEvent.VK_UP -> upPressed = true
            KeyEvent.VK_DOWN -> downPressed = true
            KeyEvent.VK_LEFT -> leftPressed = true
            KeyEvent.VK_RIGHT -> rightPressed = true
            KeyEvent.VK_1 -> simSpeed = 1   // Normal speed
            KeyEvent.VK_2 -> simSpeed = 10   // Fast
            KeyEvent.VK_3 -> simSpeed = 100  // Ultra Fast
            KeyEvent.VK_4 -> simSpeed = 1000 //omega fast :)
            KeyEvent.VK_0 -> simSpeed = 0
            KeyEvent.VK_ENTER -> {
                currentTrackIndex = (currentTrackIndex + 1) % trackLibrary.size
                track = trackLibrary[currentTrackIndex]
                frameCount = 0
                if (!playerMoving && !isViewingReplay) {
                    popManager.nextGeneration()
                    generationCount--
                }
                if (isViewingReplay) {
                    aiReplayFrame = 0
                    playerReplayFrame = 0
                }
                if (playerMoving) playerCar.resetPlayer(playerCar)
            }
            KeyEvent.VK_R -> {
                if (isViewingReplay) {
                    isViewingReplay = false
                    simSpeed = 1
                    frameCount = 0
                    popManager.nextGeneration()
                    generationCount--
                    mode = "Player Control"
                    playerMoving = true
                }
                else {
                    isViewingReplay = true
                    playerMoving = false
                    playerCar.resetPlayer(playerCar)
                    aiReplayFrame = 0
                    playerReplayFrame = 0
                    frameCount = 0
                    simSpeed = 0
                    mode = "Viewing Replay"
                }
            }
            KeyEvent.VK_SPACE -> {
                if (playerMoving) {
                    playerMoving = false
                    simSpeed = 1
                    frameCount = 0
                    popManager.nextGeneration()
                    generationCount--
                    mode = "AI Training"
                }
                else {
                    playerMoving = true
                    isViewingReplay = false
                    simSpeed = 0
                    aiReplayFrame = 0
                    playerReplayFrame = 0
                    playerCar.resetPlayer(playerCar)
                    frameCount = 0
                    mode = "Player Control"
                }
            }
            else -> keys.add(e.keyCode)
        }
    }
    override fun keyReleased(e: KeyEvent) {
        keys.remove(e.keyCode)
        when (e.keyCode) {
            KeyEvent.VK_UP -> upPressed = false
            KeyEvent.VK_DOWN -> downPressed = false
            KeyEvent.VK_LEFT -> leftPressed = false
            KeyEvent.VK_RIGHT -> rightPressed = false
        }
    }
    override fun keyTyped(e: KeyEvent) {}

}


class NeuralNetwork(val inputNodes: Int, val hiddenNodes: Int, val outputNodes: Int) {
    // Matrices for weights
    var weightsIH = Array(hiddenNodes) { DoubleArray(inputNodes) { Math.random() * 2 - 1 } }
    var weightsHO = Array(outputNodes) { DoubleArray(hiddenNodes) { Math.random() * 2 - 1 } }

    // Biases
    var biasH = DoubleArray(hiddenNodes) { Math.random() * 2 - 1 }
    var biasO = DoubleArray(outputNodes) { Math.random() * 2 - 1 }

    fun feedForward(inputArray: DoubleArray): DoubleArray {
        // 1. Calculate Hidden Layer
        val hidden = DoubleArray(hiddenNodes)
        for (i in 0 until hiddenNodes) {
            var sum = 0.0
            for (j in 0 until inputNodes) {
                sum += inputArray[j] * weightsIH[i][j]
            }
            hidden[i] = tanh(sum + biasH[i]) // Activation
        }

        // 2. Calculate Output Layer
        val output = DoubleArray(outputNodes)
        for (i in 0 until outputNodes) {
            var sum = 0.0
            for (j in 0 until hiddenNodes) {
                sum += hidden[j] * weightsHO[i][j]
            }
            output[i] = tanh(sum + biasO[i]) // Activation
        }
        return output
    }

    fun mutate(rate: Double) {
        // Helper function to apply a small random change
        fun mutateValue(v: Double): Double {
            return if (Math.random() < rate) {
                // Add a small random decimal between -0.1 and 0.1
                v + (Math.random() * 2 - 1) * 0.1
            } else v
        }

        // 1. Mutate Weights: Input -> Hidden
        for (i in weightsIH.indices) {
            for (j in weightsIH[i].indices) {
                weightsIH[i][j] = mutateValue(weightsIH[i][j])
            }
        }

        // 2. Mutate Weights: Hidden -> Output
        for (i in weightsHO.indices) {
            for (j in weightsHO[i].indices) {
                weightsHO[i][j] = mutateValue(weightsHO[i][j])
            }
        }

        // 3. Mutate Hidden Biases
        for (i in biasH.indices) {
            biasH[i] = mutateValue(biasH[i])
        }

        // 4. Mutate Output Biases
        for (i in biasO.indices) {
            biasO[i] = mutateValue(biasO[i])
        }
    }
}

var generationCount = 1
class GenerationManager(val populationSize: Int, val startX: Double, val startY: Double, val startAngle: Double) {
    var cars = mutableListOf<RaceCar>()
    var bestFitnessOfAllTime = 0.0
    var bestBrainOfAllTime: NeuralNetwork? = null



    init {
        // Initial random population
        for (i in 0 until populationSize) {
            cars.add(RaceCar(track.startX, track.startY))
        }
    }

    fun nextGeneration() {
        // 1. Find the winner
        val sortedCars = cars.sortedWith(compareByDescending<RaceCar> { it.nextCheckpointIndex }
            .thenBy { it.distToNextCheckpoint } // Smaller distance to the goal is better
            .thenBy { it.frameCount })       // Then faster time
        val winner = sortedCars[0]


        val newCars = mutableListOf<RaceCar>()

        val removeRandom = if (generationCount > 40) true else false

        // 1. ELITISM (Top 1% - No changes)
        newCars.add(RaceCar(track.startX, track.startY, copyBrain(winner.brain)))

        // 2. FINE-TUNING (30% - Low mutation)
        var repeat = if (removeRandom) 0.5 else 0.4
        repeat((populationSize * repeat).toInt()) {
            val brain = copyBrain(winner.brain)
            brain.mutate(0.05) // Very small tweaks to perfect the racing line
            newCars.add(RaceCar(track.startX, track.startY, brain))
        }

        repeat = if (removeRandom) 0.49 else 0.4
        // 3. EXPLORATION (40% - High mutation)
        repeat((populationSize * repeat).toInt()) {
            val brain = copyBrain(winner.brain)
            brain.mutate(0.5) // Big changes to find new ways around corners (normally 20%)
            newCars.add(RaceCar(track.startX, track.startY, brain))
        }

        repeat = if (removeRandom) 0.0 else 0.19
        // 4. RANDOM REBOOT (19% - Completely New)
        repeat((populationSize * repeat).toInt()) {
            newCars.add(RaceCar(track.startX, track.startY, NeuralNetwork(6, 12, 2)))
        }

        // Switch tracks every 10 generations
        if (generationCount % 10 == 0) {
//            currentTrackIndex = (currentTrackIndex + 1) % trackLibrary.size
            track = trackLibrary[currentTrackIndex]

            for (car in newCars) {
                car.x = track.startX
                car.y = track.startY
                car.angle = track.startAngle
                car.nextCheckpointIndex = 0
            }
        }


        cars = newCars
        generationCount++
    }

    // Helper to deep-copy a brain so mutations don't affect the original
    private fun copyBrain(original: NeuralNetwork): NeuralNetwork {
        val copy = NeuralNetwork(original.inputNodes, original.hiddenNodes, original.outputNodes)
        for (i in original.weightsIH.indices) copy.weightsIH[i] = original.weightsIH[i].copyOf()
        for (i in original.weightsHO.indices) copy.weightsHO[i] = original.weightsHO[i].copyOf()
        copy.biasH = original.biasH.copyOf()
        copy.biasO = original.biasO.copyOf()
        return copy
    }
}

fun main() {
    val frame = JFrame("Kotlin 2D Racer")
    val panel = GamePanel()

    // 1. Set the preferred size of the content
    panel.preferredSize = Dimension(1200, 800) // Matches your track coordinates better

    frame.add(panel)
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

    // 2. Use pack() to make the window fit the panel perfectly
    frame.pack()

    frame.setLocationRelativeTo(null) // Center on screen
    frame.isVisible = true
}