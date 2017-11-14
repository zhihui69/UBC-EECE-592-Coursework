package MyRobots;

import com.sun.xml.internal.bind.v2.model.core.ElementPropertyInfo;
import robocode.*;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.*;
import java.util.HashMap;
import java.util.Random;
import java.util.Set;

import static java.awt.event.KeyEvent.*;

/**
 * In order to allow this particular robot to work, robocode.robot.filesystem.quota=4294967296
 * was set in robocode.properties in order to allow large files to be loaded and saved.
 */

public class MettaBotLUT extends AdvancedRobot //Robot
{
    // Learning constants for Q function
    private static final double ALPHA = 0.1;
    private static final double GAMMA = 0.8;
    private static final double EPSILON = 0.1;
    // Misc. constants used in the robot
    private static final int ARENA_SIZEX_PX = 800;
    private static final int ARENA_SIZEY_PX = 600;
    private static final int ENEMY_ENERGY_THRESHOLD = 50;
    private static final int NULL_32 = 0xFFFFFFFF;

    // Move actions
    private static final int MOVE_UP = 0;
    private static final int MOVE_DN = 1;
    private static final int MOVE_LT = 2;
    private static final int MOVE_RT = 3;
    private static final int MOVE_NUM = 4;
    private static final int MOVE_DISTANCE = 50;
    // Fire actions
    private static final int FIRE_0  = 0;
    private static final int FIRE_1  = 1;
    private static final int FIRE_3  = 2;
    private static final int FIRE_NUM = 3;
    // Aim actions
    private static final int AIM_ST  = 0;
    private static final int AIM_LT  = 1;
    private static final int AIM_RT  = 2;
    private static final int AIM_NUM = 3;
    private static final int AIM_MOD = 10;          // Adds a degree offset to the aim

    // State hash field and offsets
    // TODO...
    // Action hash field and offsets
    private static final int ACTION_MOVE_OFFSET = 0;
    private static final int ACTION_MOVE_WIDTH = 2;
    private static final int ACTION_AIM_OFFSET = 2;
    private static final int ACTION_AIM_WIDTH = 2;
    private static final int ACTION_FIRE_OFFSET = 4;
    private static final int ACTION_FIRE_WIDTH = 2;
    private static final int ACTION_FIELD_WIDTH = 6;
    private static final int ACTION_FIELD_OFFSET = 20;

    // Learning constants
    private static final boolean NON_TERMINAL_STATE = false;
    private static final boolean TERMINAL_STATE = true;
    private static final int SARSA_GREEDY = 0;      // On-policy greedy SARSA
    private static final int SARSA_EXPLORATION = 1; // On-policy SARSA with 1-EPSILON exploration
    private static final int Q_GREEDY = 2;          // Off-policy greedy Q-learning
    private static final int Q_EXPLORATION = 3;     // Off-policy Q-learning with 1-EPSILON exploration

    // LUT file and properties
    private static final String lutFileName = "./ass2lut.dat";
    private File mLutFile;

    // State variables
    private boolean mDebug = true;
    private int mCurrentLearningPolicy = Q_GREEDY;

    // Variables to track the state of the arena
    private int mRobotX;
    private int mRobotY;
    private int mRobotHeading;
    private int mRobotGunHeading;
    private int mRobotGunBearing;
    private int mRobotEnergy;
    private int mEnemyDistance;
    private int mEnemyHeading;
    private int mEnemyBearing;
    private int mEnemyBearingFromGun;
    private int mEnemyEnergy;
    private int mEnemyX;
    private int mEnemyY;

    // Variables for learning
    private int mPreviousStateActionHash;
    private int mCurrentStateActionHash = NULL_32;
    private double mCurrentReward;

    private final Random mRandomInt = new Random();

    // Hashmap to store state/action probabilities
    private static HashMap<Integer, Double> mReinforcementLearningLUTHashMap = new HashMap<>();

    // Completion conditions
    private final TurnCompleteCondition mTurnComplete = new TurnCompleteCondition(this);
    private final MoveCompleteCondition mMoveComplete = new MoveCompleteCondition(this);
    private final GunTurnCompleteCondition mGunMoveComplete = new GunTurnCompleteCondition(this);

    // Convenience class to store a state/action hash along with its components
    private class StateActionHashObject
    {
        int mActionHash;
        int mStateHash;
        int mCompleteHash;
        double mQValue;

        StateActionHashObject(int actionHash, int stateHash, int completeHash, double qValue)
        {
            mActionHash = actionHash;
            mStateHash = stateHash;
            mCompleteHash = completeHash;
            mQValue = qValue;
        }
    }

    public void run()
    {
        Set<Integer> keys;

        // Set colours
        setColors(Color.PINK, Color.PINK, Color.PINK, Color.PINK, Color.PINK);
        // Set robot properties
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        // If the current hashmap is empty, we need to load from the file
        mLutFile = getDataFile(lutFileName);
        if(mReinforcementLearningLUTHashMap.isEmpty())
        {
            loadLut(mLutFile);
        }

        //keys = mReinforcementLearningLUTHashMap.keySet();
        //
        //for(Integer i: keys)
        //{
        //    printDebug("0x%08x %f\n", i, mReinforcementLearningLUTHashMap.get(i));
        //}
        //
        //printDebug("0x%08x %f\n", 5, mReinforcementLearningLUTHashMap.get(5));

        printDebug("Data available: %d bytes\n", getDataQuotaAvailable());

        for(;;)
        {
            turnRadarRight(360);
        }
    }

    // Called when a key has been pressed
    public void onKeyPressed(java.awt.event.KeyEvent e)
    {
        switch (e.getKeyCode())
        {
            // Nothing here for now
        }
    }

    // Called when a key has been released (after being pressed)
    public void onKeyReleased(KeyEvent e)
    {
        switch (e.getKeyCode())
        {
            case VK_G:
                mCurrentLearningPolicy = Q_GREEDY;
                printDebug("Current learning policy is greedy\n");
                break;
            case VK_E:
                mCurrentLearningPolicy = Q_EXPLORATION;
                printDebug("Current learning policy is exploration with epsilon of %f\n", EPSILON);
                break;
            case VK_D:
                if(mDebug)
                {
                    mDebug = false;
                }
                else
                {
                    mDebug = true;
                }
                break;
        }
    }

    public void onScannedRobot(ScannedRobotEvent event)
    {
        double angle;

        printDebug("==[SCAN]==========================================\n");
        // Obtain state information
        // Robot's info
        mRobotX = (int)getX();
        mRobotY = (int)getY();
        mRobotHeading = (int)getHeading();
        mRobotGunHeading = (int)getGunHeading();
        mRobotGunBearing = normalizeAngle(mRobotHeading - mRobotGunHeading);
        mRobotEnergy = (int)getEnergy();

        // Enemy's info
        mEnemyDistance = (int)event.getDistance();
        mEnemyHeading = (int)event.getHeading();
        mEnemyBearing = (int)event.getBearing();
        mEnemyBearingFromGun = normalizeAngle(mRobotGunBearing + mEnemyBearing);
        mEnemyEnergy = (int)event.getEnergy();
        // Calculate the enemy's last know position
        // Calculate the angle to the scanned robot
        angle = Math.toRadians(getHeading() + event.getBearing() % 360);
        // Calculate the coordinates of the robot
        mEnemyX = (int)(getX() + Math.sin(angle) * event.getDistance());
        mEnemyY = (int)(getY() + Math.cos(angle) * event.getDistance());


        printDebug("Robot: X %d Y %d Heading %d GunHeading %d Energy %d\n",
            mRobotX, mRobotY, mRobotHeading, mRobotGunHeading, mRobotEnergy);
        printDebug("Enemy: Distance %d Heading %d Bearing %d BearingFromGun %d Energy %d\n",
            mEnemyDistance, mEnemyHeading, mEnemyBearing, mEnemyBearingFromGun, mEnemyEnergy);

        learn(NON_TERMINAL_STATE);
    }

    private void learn(boolean terminalState)
    {
        double qPrevNew, randomDouble;
        int currentStateHash, randomActionHash;
        StateActionHashObject maxQHashObject;

        printDebug("==[LEARN]=========================================\n");

        // Calculate the current reward
        // Reward can be obtained asynchronously through events such as bullet hitting
        // We will add the delta between robots here for the final reward
        // Negative means the opponent has more life than the player, and vice versa
        mCurrentReward += (mRobotEnergy - mEnemyEnergy);
        printDebug("Current reward: %f\n", mCurrentReward);

        // We don't do this on the first learn
        if(mCurrentStateActionHash != NULL_32)
        {
            // Copy current state hash into previous
            mPreviousStateActionHash = mCurrentStateActionHash;
        }
        // Determine current state hash
        currentStateHash = generateStateHash();

        // Choose an action hash that has the maximum Q for this state
        maxQHashObject = getMaxQActionHash(currentStateHash);

        // We don't do this on the first learn
        if(mCurrentStateActionHash != NULL_32)
        {
            // Calculate new value for previous Q;
            qPrevNew = calculateQPrevNew(maxQHashObject.mQValue);
            // Update the hashmap with the new value for the previous Q
            mReinforcementLearningLUTHashMap.put(mPreviousStateActionHash, qPrevNew);
        }

        // Reset reward until the next learn
        mCurrentReward = 0.0;

        // We're done! No more actions.
        if(terminalState)
        {
            printDebug("Terminal state! No more actions available.\n");
            return;
        }

        // Choose an action based on policy
        if(mCurrentLearningPolicy == Q_GREEDY)
        {
            printDebug("Q-learning, greedy\n");
            // Parse and run the action with the highest Q value (greedy action)
            // Update current state/action hash
            printDebug("Taking greedy action of 0x%02x\n", maxQHashObject.mActionHash);
            mCurrentStateActionHash = maxQHashObject.mCompleteHash;
            parseActionHash(maxQHashObject.mActionHash);
        }
        else if(mCurrentLearningPolicy == Q_EXPLORATION)
        {
            printDebug("Q-learning, exploration\n");
            // Roll the dice
            randomDouble = getRandomDouble(0.0, 1.0);
            if(randomDouble < EPSILON)
            {
                // Take random action
                randomActionHash = getRandomAction();
                printDebug("Taking random action of 0x%02x\n", randomActionHash);
                // Update current state/action hash
                mCurrentStateActionHash = updateIntField(currentStateHash, ACTION_FIELD_WIDTH, ACTION_FIELD_OFFSET, randomActionHash);
                parseActionHash(randomActionHash);
            }
            else
            {
                // Take greedy action
                printDebug("Taking greedy action of 0x%02x\n", maxQHashObject.mActionHash);
                // Update current state/action hash
                mCurrentStateActionHash = maxQHashObject.mCompleteHash;
                parseActionHash(maxQHashObject.mActionHash);
            }
        }
    }

    private double calculateQPrevNew(double qValMax)
    {
        double qPrevNew, qPrevOld;

        qPrevOld = mReinforcementLearningLUTHashMap.get(mPreviousStateActionHash);
        qPrevNew = qPrevOld + (ALPHA * (mCurrentReward + (GAMMA * qValMax) - qPrevOld));

        return qPrevNew;
    }

    /**
     * Obtain the action hash with the largest Q value based on the provided action hash.
     * If multiple actions are tied for the largest Q value, pick one at random.
     * @param currentStateHash
     * @return
     */
    private StateActionHashObject getMaxQActionHash(int currentStateHash)
    {
        int moveAction, fireAction, aimAction;
        int randomPick, actionHash, completeHash, selectedActionHash, selectedCompleteHash;
        int[] qMaxActions;
        int currentQMaxActionNum = 0;
        double currentMax = -999.0;
        StateActionHashObject selection;

        qMaxActions = new int[MOVE_NUM * FIRE_NUM * AIM_NUM];

        // Iterate through all possible actions
        printDebug("Current state hash: 0x%08x\n", currentStateHash);
        printDebug("Possible Q-values:\n");
        for(moveAction = 0; moveAction < MOVE_NUM; moveAction++)
        {
            for(fireAction = 0; fireAction < FIRE_NUM; fireAction++)
            {
                for(aimAction = 0; aimAction < AIM_NUM; aimAction++)
                {
                    // Calculate the action hash and create the complete hash by adding it to the current state hash
                    actionHash = generateActionHash(moveAction, fireAction, aimAction);
                    completeHash = updateIntField(currentStateHash, ACTION_FIELD_WIDTH, ACTION_FIELD_OFFSET, actionHash);
                    // Make the entry 0.0 if it doesn't exist
                    mReinforcementLearningLUTHashMap.putIfAbsent(completeHash, 0.0);
                    printDebug("0x%08x: %f\n", completeHash, mReinforcementLearningLUTHashMap.get(completeHash));
                    // Update current max
                    if(mReinforcementLearningLUTHashMap.get(completeHash) > currentMax)
                    {
                        // New max, clear array
                        // We can have a maximum of the number of possible actions as the number of possible actions
                        qMaxActions = new int[MOVE_NUM * FIRE_NUM * AIM_NUM];
                        currentQMaxActionNum = 1;
                        qMaxActions[currentQMaxActionNum - 1] = completeHash;
                        currentMax = mReinforcementLearningLUTHashMap.get(completeHash);
                    }
                    else if(mReinforcementLearningLUTHashMap.get(completeHash) == currentMax)
                    {
                        currentQMaxActionNum++;
                        qMaxActions[currentQMaxActionNum - 1] = completeHash;
                    }
                }
            }
        }

        printDebug("Max actions: %d\n", currentQMaxActionNum);

        if(currentQMaxActionNum == 1)
        {
            selectedCompleteHash = qMaxActions[0];
            selectedActionHash = getIntFieldVal(selectedCompleteHash, ACTION_FIELD_WIDTH, ACTION_FIELD_OFFSET);
            printDebug("Found best possible action to take [0x%02x] with Q-value of %f\n",
                qMaxActions[0], mReinforcementLearningLUTHashMap.get(selectedCompleteHash));
        }
        else
        {
            randomPick = getRandomInt(0, currentQMaxActionNum-1);
            //printDebug("Random pick: %d\n", randomPick);
            selectedCompleteHash = qMaxActions[randomPick];
            selectedActionHash = getIntFieldVal(selectedCompleteHash, ACTION_FIELD_WIDTH, ACTION_FIELD_OFFSET);
            printDebug("Found %d possible actions to take, randomly picking index %d [0x%02x] with Q-value of %f\n",
                currentQMaxActionNum, randomPick, selectedActionHash, mReinforcementLearningLUTHashMap.get(selectedCompleteHash));
        }

        // Combine the selected hash along with its Q value into the convenience object
        selection = new StateActionHashObject(selectedActionHash, currentStateHash, selectedCompleteHash, mReinforcementLearningLUTHashMap.get(selectedCompleteHash));

        return selection;
    }

    public void onBulletHit(BulletHitEvent event)
    {
        printDebug("I hit " + event.getName() + "!\n");
        mCurrentReward += 30;
    }

    public void onBattleEnded(BattleEndedEvent event)
    {
        // Stop any ongoing actions
        stop();
        // Save the LUT to the data file
        saveLut(mLutFile);
    }

    public void onDeath(DeathEvent event)
    {

        // Give terminal reward of -100
        mCurrentReward -= 100;
        learn(TERMINAL_STATE);
    }

    public void onWin(WinEvent event)
    {
        // Give terminal reward of 100
        mCurrentReward += 100;
        learn(TERMINAL_STATE);
    }

    /**
     * Normalize an angle
     * @param angle The angle to normalize
     * @return The normalized angle
     */
    private int normalizeAngle(int angle)
    {
        int result = angle;

        while (result >  180) result -= 360;
        while (result < -180) result += 360;

        return result;
    }

    /**
     * This generates a hash for a given state. Everything is encoded in an int
     * @return a hash representing the current state
     */
    public int generateStateHash()
    {
        int stateHash = 0;

        int quantRobotX;
        int quantRobotY;
        int quantDistance;
        int quantRobotHeading;
        int quantEnemyBearingFromGun;
        int quantEnemyEnergy;

        // Legend: [max] -> quantization -> field width
        // Current position X                       [800]   -> 16   -> 4
        // Current position Y                       [600]   -> 16   -> 4
        // Distance between robot and opponent      [1000]  -> 8    -> 3
        // Robot bearing                            [360]   -> 16   -> 4
        // Enemy bearing                            [360]   -> 16   -> 4
        // Energy of enemy                          [N/A]   -> 2    -> 1
        // Total space                                                20

        // Quantization
        quantRobotX = quantizeInt(mRobotX, ARENA_SIZEX_PX, 16);
        quantRobotY = quantizeInt(mRobotY, ARENA_SIZEY_PX, 16);
        quantDistance = quantizeInt(mEnemyDistance, 1000, 8);
        quantRobotHeading = quantizeInt(mRobotHeading, 360, 16);
        quantEnemyBearingFromGun = quantizeInt(mEnemyBearingFromGun + 180, 360, 16);
        // For enemy energy, we will only care if it's above a threshold
        if(mEnemyEnergy > ENEMY_ENERGY_THRESHOLD)
        {
            quantEnemyEnergy = 0;
        }
        else
        {
            quantEnemyEnergy = 1;
        }

        // Assemble the hash
        stateHash = updateIntField(stateHash, 4, 0, quantRobotX);
        stateHash = updateIntField(stateHash, 4, 4, quantRobotY);
        stateHash = updateIntField(stateHash, 3, 7, quantDistance);
        stateHash = updateIntField(stateHash, 4, 11, quantRobotHeading);
        stateHash = updateIntField(stateHash, 4, 15, quantEnemyBearingFromGun);
        stateHash = updateIntField(stateHash, 1, 19, quantEnemyEnergy);

        printDebug("Quantized values: %d %d %d %d %d %d\n",
            quantRobotX, quantRobotY, quantDistance, quantRobotHeading, quantEnemyBearingFromGun, quantEnemyEnergy);
        printDebug("State hash: 0x%08x\n", stateHash);

        // Check if any values are negative, something went wrong
        if( (quantRobotX < 0) || (quantRobotY < 0) ||
        (quantDistance < 0) || (quantRobotHeading < 0) ||
        (quantEnemyBearingFromGun) < 0 || (quantEnemyEnergy < 0))
        {
            throw new ArithmeticException("Quantized value cannot be negative!!!");
        }

        return stateHash;
    }

    /**
     * This generates a hash for a given action. Everything is encoded in an int
     * @return Returns a hash based on the selected action
     */
    public int generateActionHash(int moveAction, int fireAction, int aimAction)
    {
        // Robot can do three things simultaneously:
        // Move up, down, left, or right                        (4)
        // Don't fire, fire 1, or fire 3                        (3)
        // Aim directly, or at some offset in either direction  (3)
        // 4 * 3 * 3 = 36 action possibilities, need at least 6 bits
        int actionHash = 0;

        actionHash = updateIntField(actionHash, ACTION_MOVE_WIDTH, ACTION_MOVE_OFFSET, moveAction);
        actionHash = updateIntField(actionHash, ACTION_FIRE_WIDTH, ACTION_FIRE_OFFSET, fireAction);
        actionHash = updateIntField(actionHash, ACTION_AIM_WIDTH, ACTION_AIM_OFFSET, aimAction);

        //printDebug("Action hash: 0x%08x\n", actionHash);

        return actionHash;
    }

    /**
     * This parses the actions to take based on a given action hash.
     * @param actionHash The action hash to parse into action
     */
    private void parseActionHash(int actionHash)
    {
        int moveDirection, fireType, aimType, newHeading, estimatedEnemyBearingFromGun;
        double angle;

        moveDirection = getIntFieldVal(actionHash, ACTION_MOVE_WIDTH, ACTION_MOVE_OFFSET);
        fireType = getIntFieldVal(actionHash, ACTION_FIRE_WIDTH, ACTION_FIRE_OFFSET);
        aimType = getIntFieldVal(actionHash, ACTION_AIM_WIDTH, ACTION_AIM_OFFSET);

        // Perform the move action
        newHeading = -1 * normalizeAngle((int)getHeading());
        switch(moveDirection)
        {
            case MOVE_UP:
                break;
            case MOVE_DN:
                newHeading = normalizeAngle(newHeading + 180);
                break;
            case MOVE_LT:
                newHeading = normalizeAngle(newHeading + 270);
                break;
            case MOVE_RT:
                newHeading = normalizeAngle(newHeading + 90);
                break;
            default:
                // We should never be in here, do nothing.
                break;
        }
        setTurnRight(newHeading);
        //turnRight(newHeading);
        // Execute the turn
        execute();
        waitFor(mTurnComplete);
        setAhead(MOVE_DISTANCE);
        //ahead(MOVE_DISTANCE);
        // Execute the ahead
        execute();
        waitFor(mMoveComplete);

        // Re-calculate the enemy's bearing based on its last know position
        // calculate gun turn to predicted x,y location

        angle = absoluteBearing((int)getX(), (int)getY(), mEnemyX, mEnemyY);
        // turn the gun to the predicted x,y location
        estimatedEnemyBearingFromGun = normalizeAngle((int)(angle - getGunHeading()));

        // Perform the aim type action
        switch(aimType)
        {
            case AIM_ST:
                // Aim directly for the enemy
                //setTurnGunRight(estimatedEnemyBearingFromGun);
                turnGunRight(estimatedEnemyBearingFromGun);
                break;
            case AIM_LT:
                // Aim to the left of the enemy by a modifier
                //setTurnGunRight(estimatedEnemyBearingFromGun - AIM_MOD);
                turnGunRight(estimatedEnemyBearingFromGun - AIM_MOD);
                break;
            case AIM_RT:
                // Aim to the ri ght of the enemy by a modifier
                //setTurnGunRight(estimatedEnemyBearingFromGun + AIM_MOD);
                turnGunRight(estimatedEnemyBearingFromGun + AIM_MOD);
                break;
            default:
                // We should never be in here, do nothing.
                break;
        }

        // Execute the aim right away
        execute();
        waitFor(mGunMoveComplete);

        // Perform the firing type action
        switch(fireType)
        {
            case FIRE_0:
                // We don't fire in this case
                break;
            case FIRE_1:
                // Fire a 1 power bullet
                setFireBullet(1.0);
                //fireBullet(1.0);
                break;
            case FIRE_3:
                // Fire a 3 power bullet
                setFireBullet(3.0);
                //fireBullet(3.0);
                break;
            default:
                // We should never be in here, do nothing.
                break;
        }
        // Execute the fire action
        execute();
    }

    /**
     * Get a random action by randomizing an action hash
     */
    private int getRandomAction()
    {
        int actionHash, randomMove, randomFire, randomAim;

        randomMove = getRandomInt(0, MOVE_NUM);
        randomFire = getRandomInt(0, FIRE_NUM);
        randomAim = getRandomInt(0, AIM_NUM);

        actionHash = generateActionHash(randomMove, randomFire, randomAim);

        return actionHash;
    }


    /**
     * Returns an absolute bearing between two points
     * @param x0 Point 0's x coordinate
     * @param y0 Point 0's y coordinate
     * @param x1 Point 1's x coordinate
     * @param y1 Point 1's y coordinate
     * @return Returns an absolute bearing based on the coordinates entered
     */
    private double absoluteBearing(int x0, int y0, int x1, int y1)
    {
        int xo = x1 - x0;
        int yo = y1 - y0;
        double hyp = calculateDistance(x0, y0, x1, y1);
        double asin = Math.toDegrees(Math.asin(xo / hyp));
        double bearing = 0;

        if (xo > 0 && yo > 0)
        {
            // both pos: lower-Left
            bearing = asin;
        }
        else if (xo < 0 && yo > 0)
        {
            // x neg, y pos: lower-right
            bearing = 360 + asin; // arcsin is negative here, actually 360 - ang
        }
        else if (xo > 0 && yo < 0)
        {
            // x pos, y neg: upper-left
            bearing = 180 - asin;
        }
        else if (xo < 0 && yo < 0)
        {
            // both neg: upper-right
            bearing = 180 - asin; // arcsin is negative here, actually 180 + ang
        }

        return bearing;
    }

    /**
     * Returns the distance between two coordinates
     * @param x0 X position of the first coordinate
     * @param y0 Y position of the first coordinate
     * @param x1 X position of the second coordinate
     * @param y1 Y position of the second coordinate
     * @return A double value representing the distance between two coordinates
     */
    private double calculateDistance(int x0, int y0, int x1, int y1)
    {
        double distance;

        distance = Math.sqrt(Math.pow((x1 - x0), 2) + Math.pow((y1 - y0), 2));

        return distance;
    }

    /**
     * Updates a field in an int
     * @param inputInteger The input integer to modify
     * @param fieldWidth The width of the field to modify
     * @param fieldOffset The field's offset
     * @param value The value to update into the field
     * @return The updated input integer
     */
    private int updateIntField(int inputInteger, int fieldWidth, int fieldOffset, int value)
    {
        int returnValue;
        int mask;

        returnValue = inputInteger;

        // Create mask
        mask = ~(((1<<fieldWidth) - 1) << fieldOffset);
        // Mask out field from input
        returnValue &= mask;
        // OR in the new value into the field
        returnValue |= value << fieldOffset;

        return returnValue;
    }

    /**
     * Returns the value of a field in an int
     * @param inputInteger The input integer to extract the value from
     * @param fieldWidth The width of the field to extract
     * @param fieldOffset The offset of the field to extract
     * @return Returns the value in the selected field
     */
    private int getIntFieldVal(int inputInteger, int fieldWidth, int fieldOffset)
    {
        int returnValue;
        int mask;

        returnValue = inputInteger;

        // Create mask
        mask = ((1<<fieldWidth) - 1) << fieldOffset;
        // Mask out the field from the input
        returnValue &= mask;
        // Shift down to grab it
        returnValue >>>= fieldOffset;

        return returnValue;
    }

    /**
     * Returns a random double value between specified min and max values
     * @param min minimum number random number can be
     * @param max maximum number random number can be
     * @return a random double between specified min and max
     */
    private double getRandomDouble(double min, double max)
    {
        double random, result;

        random = new Random().nextDouble();
        result = min + (random * (max - min));

        return result;
    }

    /**
     * Returns a random integer value between specified min and max values
     * @param min minimum number random number can be inclusive
     * @param max maximum number random number can be inclusive
     * @return a random integer between specified min and max
     */
    private int getRandomInt(int min, int max)
    {
        int result;
        Random random;

        random = new Random();
        result = random.nextInt(max - min + 1) + min;

        return result;
    }

    /**
     * Quantizes an integer based on its current max and the desired quantization max
     * @param value The value to be quantized
     * @param realMax The actual maximum of the value
     * @param quantizedMax The desired quantized maximum for the value
     * @return The quantized version of the value
     */
    private int quantizeInt(int value, int realMax, int quantizedMax)
    {
        int quantizedVal;

        quantizedVal = (int)((double)value * (double)quantizedMax / (double)realMax);

        return quantizedVal;
    }

    /**
     * Conditionally prints a message if the debug flag is on
     * @param format The string to format
     * @param arguments The string format's variables
     */
    private void printDebug(String format, Object... arguments)
    {
        if(mDebug)
        {
            System.out.format(format, arguments);
        }
    }

    /**
     * Save the lookup table hashmap
     * @param lutFile The filename to use for the lookup table hashmap
     */
    private void saveLut(File lutFile)
    {
        try
        {
            printDebug("Saving LUT to file...\n");
            RobocodeFileOutputStream fileOut = new RobocodeFileOutputStream(lutFile);
            ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(fileOut));
            out.writeObject(mReinforcementLearningLUTHashMap);
            out.close();
            fileOut.close();
        }
        catch (IOException exception)
        {
            exception.printStackTrace();
        }
    }

    /**
     * Load the lookup table hashmap
     * @param lutFile The filename to use for the lookup table hashmap
     */
    private void loadLut(File lutFile)
    {
        try
        {
            printDebug("Loading LUT from file...\n");
            FileInputStream fileIn = new FileInputStream(lutFile);
            ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(fileIn));
            mReinforcementLearningLUTHashMap = (HashMap<Integer,Double>)in.readObject();
            in.close();
            fileIn.close();
        }
        catch (IOException exception)
        {
            exception.printStackTrace();
        }
        catch (ClassNotFoundException exception)
        {
            exception.printStackTrace();
        }
    }
}