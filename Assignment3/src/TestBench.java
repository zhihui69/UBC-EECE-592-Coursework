import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;

class TestBench
{
    // Constants for assignment questions
    // Trials to run to obtain convergence average
    private static final int CONVERGENCE_AVERAGE_TRIALS = 500;
    // Number of epochs to test to before bailing
    private static final int MAXIMUM_EPOCHS = 10000;
    // Number of NN inputs
    private static final int NUM_INPUTS = 4;
    // Number of NN hidden neurons
    private static final int NUM_HIDDEN_NEURONS = 10;
    // Number of NN outputs
    private static final int NUM_OUTPUTS = 5;
    // Squared error to b
    private static final double CONVERGENCE_ERROR = 0.05;

    // NN parameters
    private static final int MIN_VAL = -1;
    private static final int MAX_VAL = 1;
    private static final double MOMENTUM = 0.9;
    private static final double LEARNING_RATE = 0.2;
    private static final double WEIGHT_INIT_MIN = -1.5;
    private static final double WEIGHT_INIT_MAX = 1.5;

    // LUT file and properties
    private static final String LUT_FILE_NAME = "1MSARSA.dat";
    private static File mLutFile;

    // LUT Hashmap to store state/action probabilities
    private static HashMap<Integer, Double> mReinforcementLearningLUTHashMap = new HashMap<>();
    private static HashMap<Integer, Integer> mStateToBestActionMap = new HashMap<>();
    private static boolean mDebug = true;

    // LUT hash encodings
    private static final int ACTION_FIELD_WIDTH = 3;
    private static final int ACTION_FIELD_OFFSET = 16;
    // Action hash field and offsets
    private static final int ACTION_MOVE_OFFSET = 0;
    private static final int ACTION_MOVE_WIDTH = 2;
    private static final int ACTION_FIRE_OFFSET = 2;
    private static final int ACTION_FIRE_WIDTH = 1;
    private static final int STATE_FIELD_WIDTH = 16;
    private static final int STATE_FIELD_OFFSET = 0;

    // Move actions
    private static final int ACTION_MOVE_UP = 0;
    private static final int ACTION_MOVE_DN = 1;
    private static final int ACTION_MOVE_LT = 2;
    private static final int ACTION_MOVE_RT = 3;
    private static final int ACTION_MOVE_NUM = 4;
    private static final int ACTION_MOVE_DISTANCE = 50;
    // Fire actions
    private static final int ACTION_FIRE_0 = 0;
    private static final int ACTION_FIRE_3 = 1;
    private static final int ACTION_FIRE_NUM = 2;

    // Misc. constants used in the robot
    private static final int ARENA_SIZEX_PX = 800;
    private static final int ARENA_SIZEY_PX = 600;

    // State hash field and offsets
    // Current position X                       [800]   -> 16   -> 4
    // Current position Y                       [600]   -> 16   -> 4
    // Distance between robot and opponent      [1000]  -> 16   -> 4
    // Robot heading                            [360]   -> 16   -> 4
    private static final int STATE_POS_X_WIDTH = 4;
    private static final int STATE_POS_X_OFFSET = 0;
    private static final int STATE_POS_X_MAX = ARENA_SIZEX_PX;

    private static final int STATE_POS_Y_WIDTH = 4;
    private static final int STATE_POS_Y_OFFSET = 4;
    private static final int STATE_POS_Y_MAX = ARENA_SIZEY_PX;

    private static final int STATE_DISTANCE_WIDTH = 4;
    private static final int STATE_DISTANCE_OFFSET = 8;
    private static final int STATE_DISTANCE_MAX = 1000;

    private static final int STATE_ROBOT_HEADING_WIDTH = 4;
    private static final int STATE_ROBOT_HEADING_OFFSET = 12;
    private static final int STATE_ROBOT_HEADING_MAX = 360;

    public static void main(String[] args) throws IOException
    {
        NeuralNet neuralNetObj;
        ArrayList<Double> results = new ArrayList<>();
        ArrayList<Integer> visitedStates = new ArrayList<>();
        int state, action, maxQAction, moveAction, fireAction, completeHash;
        double epochAverage, maxQVal;

        // Quantized values
        int quantRobotX;
        int quantRobotY;
        int quantDistance;
        int quantRobotHeading;

        // Intermediate values
        double robotHeadingInDegrees;

        // Bipolar values
        double bipolarRobotX;
        double bipolarRobotY;
        double bipolarDistance;
        double bipolarRobotHeading;

        mLutFile = new File(LUT_FILE_NAME);

        // Load LUT file
        loadLut(mLutFile);

        printDebug("LUT file has %d entries\n", mReinforcementLearningLUTHashMap.size());

        for (Integer fullHash : mReinforcementLearningLUTHashMap.keySet())
        {
            // Get the state
            state = getIntFieldVal(fullHash, STATE_FIELD_WIDTH, STATE_FIELD_OFFSET);
            // Check if the state has already been parsed
            if (!mStateToBestActionMap.containsKey(state))
            {
                maxQVal = -999;
                maxQAction = 0xFF; // bad value
                // Key must be parsed, get associated state/action pairs and their Qs
                for (moveAction = 0; moveAction < ACTION_MOVE_NUM; moveAction++)
                {
                    for (fireAction = 0; fireAction < ACTION_FIRE_NUM; fireAction++)
                    {
                        // Calculate the action hash and create the complete hash by adding it to the current state hash
                        action = generateActionHash(moveAction, fireAction);
                        // Generate complete hash from action and state
                        completeHash = combineStateActionHashes(state, action);
                        // Check if the Q is higher than the current highest
                        // LUT will always have a value for the current hash
                        if (mReinforcementLearningLUTHashMap.get(completeHash) > maxQVal)
                        {
                            maxQVal = mReinforcementLearningLUTHashMap.get(completeHash);
                            maxQAction = action;
                        }
                    }
                }
                // We should now have the action with the highest Q value, construct our training pair
                mStateToBestActionMap.put(state, maxQAction);
                //printDebug("Found best action 0x%1x for state 0x%08x with Q value of %3.5f\n", maxQAction, state, maxQVal);
            }
        }

        printDebug("Training set has %d entries\n", mStateToBestActionMap.size());

        // Raw training set is now obtained, need to convert values into NN friendly I/Os
        for (Integer trainingState : mStateToBestActionMap.keySet())
        {
            printDebug("State: 0x%08x Action: %x\n", trainingState, mStateToBestActionMap.get(trainingState));
            // get our quantized values
            quantRobotX = getIntFieldVal(trainingState, STATE_POS_X_WIDTH, STATE_POS_X_OFFSET);
            quantRobotY = getIntFieldVal(trainingState, STATE_POS_Y_WIDTH, STATE_POS_Y_OFFSET);
            quantDistance = getIntFieldVal(trainingState, STATE_DISTANCE_WIDTH, STATE_DISTANCE_OFFSET);
            quantRobotHeading = getIntFieldVal(trainingState, STATE_ROBOT_HEADING_WIDTH, STATE_ROBOT_HEADING_OFFSET);

            // Scale the quantizations to bipolar binary representations
            bipolarRobotX = (quantRobotX * 2.0 / 16.0) - 1.0;
            bipolarRobotY = (quantRobotY * 2.0 / 16.0) - 1.0;
            bipolarDistance = (quantDistance * 2.0 / 16.0) - 1.0;
            robotHeadingInDegrees = (quantRobotHeading * 360 / 16.0);
            bipolarRobotHeading = Math.cos(Math.toRadians(robotHeadingInDegrees));

            printDebug("%1.3f %1.3f %1.3f %1.3f\n", bipolarRobotX, bipolarRobotY, bipolarDistance, bipolarRobotHeading);


        }

        //try
        //{
        //    System.out.println("Starting...");
        //    neuralNetObj = new NeuralNet(
        //        NUM_INPUTS, NUM_OUTPUTS, NUM_HIDDEN_NEURONS, LEARNING_RATE, MOMENTUM, MIN_VAL, MAX_VAL, WEIGHT_INIT_MIN, WEIGHT_INIT_MAX);
        //    epochAverage = runTrials(neuralNetObj, BIN_XOR_TRAINING_SET_IN, BIN_XOR_TRAINING_SET_OUT, CONVERGENCE_AVERAGE_TRIALS, CONVERGENCE_ERROR, MAXIMUM_EPOCHS, results);
        //    System.out.format("%d successful trials to %1.2f total squared error convergence was average %1.3f\n", CONVERGENCE_AVERAGE_TRIALS, CONVERGENCE_ERROR, epochAverage);
        //    printTrialResults(results, "convergence.csv");
        //
        //}
        //catch (IOException e)
        //{
        //    System.out.println(e);
        //}
    }

    private static void printTrialResults(ArrayList<Double> results, String fileName) throws IOException
    {
        int epoch;
        PrintWriter printWriter = new PrintWriter(new FileWriter(fileName));
        printWriter.printf("Epoch, Total Squared Error,\n");
        for(epoch = 0; epoch < results.size(); epoch++)
        {
            printWriter.printf("%d, %f,\n", epoch, results.get(epoch));
        }
        printWriter.flush();
        printWriter.close();
    }

    private static double runTrials(NeuralNet neuralNetObj, double[][] inVecs, double[][] outVec, int numTrials, double convergenceError, int maxEpochs, ArrayList<Double> results)
    {
        int epochs, failedConvergences;
        int successfulTrials;
        double epochAverage;

        epochAverage = 0.0;
        successfulTrials = 0;
        failedConvergences = 0;
        do
        {
            // Clear our results
            results.clear();
            // Initialize weights for a new training session
            neuralNetObj.initializeWeights();
            // Attempt convergence
            epochs = attemptConvergence(
                neuralNetObj, inVecs, outVec, convergenceError, maxEpochs, results);
            // Check if we're under max epochs
            if(epochs < maxEpochs)
            {
                epochAverage += (double)epochs;
                successfulTrials++;
            }
            else
            {
                failedConvergences++;
                if(failedConvergences > 100000)
                {
                    break;
                }
            }
        }while(successfulTrials < numTrials);
        // Average out trials
        epochAverage /= successfulTrials;

        return epochAverage;
    }

    private static int attemptConvergence(NeuralNet NeuralNetObj, double[][] inVecs, double[][] outVec, double convergenceError, int maxEpochs, ArrayList<Double> results)
    {
        double cummError;
        int index, epoch;

        for (epoch = 0; epoch < maxEpochs; epoch++)
        {
            cummError = 0.0;
            for (index = 0; index < 4; index++)
            {
                //cummError += Math.pow(NeuralNetObj.train(inVecs[index], outVec[index]), 2.0);
            }

            // Append the result to our list
            results.add(cummError);

            if (cummError < convergenceError)
            {
                break;
            }
        }

        return epoch;
    }

    /**
     * Load the lookup table hashmap
     *
     * @param lutFile The filename to use for the lookup table hashmap
     */
    private static void loadLut(File lutFile)
    {
        try
        {
            printDebug("Loading LUT from file...\n");
            FileInputStream fileIn = new FileInputStream(lutFile);
            //ObjectInputStream in = new ObjectInputStream(fileIn);
            ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(fileIn));
            mReinforcementLearningLUTHashMap = (HashMap<Integer, Double>) in.readObject();
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

    /**
     * Conditionally prints a message if the debug flag is on
     *
     * @param format    The string to format
     * @param arguments The string format's variables
     */
    private static void printDebug(String format, Object... arguments)
    {
        if (mDebug)
        {
            System.out.format(format, arguments);
        }
    }

    /**
     * Combine a state and action hash together to form a complete hash
     * @param stateHash State hash
     * @param actionHash Action hash
     * @return The complete state/action hash
     */
    private static int combineStateActionHashes(int stateHash, int actionHash)
    {
        return updateIntField(stateHash, ACTION_FIELD_WIDTH, ACTION_FIELD_OFFSET, actionHash);
    }

    /**
     * Returns the value of a field in an int
     *
     * @param inputInteger The input integer to extract the value from
     * @param fieldWidth   The width of the field to extract
     * @param fieldOffset  The offset of the field to extract
     * @return Returns the value in the selected field
     */
    private static int getIntFieldVal(int inputInteger, int fieldWidth, int fieldOffset)
    {
        int returnValue;
        int mask;

        returnValue = inputInteger;

        // Create mask
        mask = ((1 << fieldWidth) - 1) << fieldOffset;
        // Mask out the field from the input
        returnValue &= mask;
        // Shift down to grab it
        returnValue >>>= fieldOffset;

        return returnValue;
    }

    /**
     * Updates a field in an int
     *
     * @param inputInteger The input integer to modify
     * @param fieldWidth   The width of the field to modify
     * @param fieldOffset  The field's offset
     * @param value        The value to update into the field
     * @return The updated input integer
     */
    private static int updateIntField(int inputInteger, int fieldWidth, int fieldOffset, int value)
    {
        int returnValue;
        int mask;

        returnValue = inputInteger;

        // Create mask
        mask = ~(((1 << fieldWidth) - 1) << fieldOffset);
        // Mask out field from input
        returnValue &= mask;
        // OR in the new value into the field
        returnValue |= value << fieldOffset;

        return returnValue;
    }

    /**
     * This generates a hash for a given action. Everything is encoded in an int
     *
     * @return Returns a hash based on the selected action
     */
    private static int generateActionHash(int moveAction, int fireAction)//, int aimAction)
    {
        // Robot can do two things simultaneously:
        // Move up, down, left, or right                        (4)
        // Don't fire or fire 3                                 (2)
        // 4 * 2 = 8 action possibilities, need at least 3 bits
        int actionHash = 0;

        actionHash = updateIntField(actionHash, ACTION_MOVE_WIDTH, ACTION_MOVE_OFFSET, moveAction);
        actionHash = updateIntField(actionHash, ACTION_FIRE_WIDTH, ACTION_FIRE_OFFSET, fireAction);

        //printDebug("Action hash: 0x%08x\n", actionHash);

        return actionHash;
    }
}